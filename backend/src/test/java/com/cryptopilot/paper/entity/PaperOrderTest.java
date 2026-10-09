package com.cryptopilot.paper.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperFill.Execution;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A paper order's life: what it locks, how it executes whole, and that a finished one stays finished. */
class PaperOrderTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-07T08:00:00Z");

    @Test
    void TR02_aBuyCostsItsValueRoundedUp_andASaleBringsItRoundedDown() {
        BigDecimal price = new BigDecimal("0.333333333333");
        BigDecimal quantity = new BigDecimal("1");

        assertThat(PaperOrder.buyCost(price, quantity)).isEqualByComparingTo("0.33333334");
        assertThat(PaperOrder.saleProceeds(price, quantity)).isEqualByComparingTo("0.33333333");
    }

    @Test
    void TR02_aWorkingBuyLocksItsCost_andAWorkingSellItsQuantity() {
        PaperOrder buy = limit(OrderSide.BUY, "100.5", "0.3");
        PaperOrder sell = limit(OrderSide.SELL, "100.5", "0.3");

        assertThat(buy.lockedAmount()).isEqualByComparingTo("30.15");
        assertThat(sell.lockedAmount()).isEqualByComparingTo("0.3");
        assertThat(buy.getMarketType()).isEqualTo(MarketType.SPOT);
        assertThat(buy.getOrderStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(buy.isOpen()).isTrue();
        assertThat(buy.isReduceOnly()).isFalse();
        assertThat(buy.isClosePosition()).isFalse();
    }

    @Test
    void TR02_aMarketOrder_locksNothing() {
        PaperOrder market = PaperOrder.spotMarket(ACCOUNT, PAIR, "k", OrderSide.BUY, BigDecimal.ONE);

        assertThat(market.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(market.getTimeInForce()).isNull();
        assertThatIllegalStateException().isThrownBy(market::lockedAmount);
    }

    @Test
    void TR02_anOrderExecutesWhole_andIsFilledThen() {
        PaperOrder order = limit(OrderSide.BUY, "100", "2");

        order.fill(new BigDecimal("2"), new BigDecimal("99"), new BigDecimal("198"), AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getExecutedQuantity()).isEqualByComparingTo("2");
        assertThat(order.getCumQuoteAmount()).isEqualByComparingTo("198");
        assertThat(order.getAvgPrice()).isEqualByComparingTo("99");
        assertThat(order.getClosedAt()).isEqualTo(AT);
        assertThat(order.getStatusReason()).isNull();
    }

    @Test
    void TR02_partOfAnOrder_doesNotExecute() {
        PaperOrder order = limit(OrderSide.BUY, "100", "2");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> order.fill(BigDecimal.ONE, new BigDecimal("99"), new BigDecimal("99"), AT));
    }

    @Test
    void TR02_aBuyByTotal_executesTheQuantityItsAmountBuys() {
        PaperOrder order = PaperOrder.spotMarketBuyFor(ACCOUNT, PAIR, "k", new BigDecimal("50"));

        order.fill(new BigDecimal("0.5"), new BigDecimal("100"), new BigDecimal("50"), AT);

        assertThat(order.getOrigQuantity()).isNull();
        assertThat(order.getQuoteOrderAmount()).isEqualByComparingTo("50");
        assertThat(order.getExecutedQuantity()).isEqualByComparingTo("0.5");
    }

    @Test
    void TR02_aCancelledOrExpiredOrder_isFinished_andCannotBeChangedAgain() {
        PaperOrder cancelled = limit(OrderSide.SELL, "100", "1");
        PaperOrder expired = limit(OrderSide.SELL, "100", "1");

        cancelled.cancel(AT);
        expired.expire("NOT_FILLABLE_ON_ARRIVAL", AT);

        assertThat(cancelled.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(cancelled.getClosedAt()).isEqualTo(AT);
        assertThat(expired.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.getStatusReason()).isEqualTo("NOT_FILLABLE_ON_ARRIVAL");
        assertThatIllegalStateException().isThrownBy(() -> cancelled.cancel(AT));
        assertThatIllegalStateException().isThrownBy(() -> expired.expire("again", AT));
        assertThatIllegalStateException()
                .isThrownBy(() -> cancelled.fill(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, AT));
    }

    @Test
    void TR02_aSizeOrPriceThatIsNotPositive_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> limit(OrderSide.BUY, "0", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> limit(OrderSide.BUY, "1", "-1"));
        assertThatNullPointerException()
                .isThrownBy(() ->
                        PaperOrder.spotLimit(ACCOUNT, PAIR, "k", OrderSide.BUY, null, BigDecimal.ONE, BigDecimal.ONE));
        assertThatNullPointerException()
                .isThrownBy(() -> PaperOrder.spotMarket(ACCOUNT, PAIR, "k", OrderSide.BUY, null));
    }

    @Test
    void TR02_aFill_copiesItsOrderAndExecution() {
        PaperOrder order = limit(OrderSide.BUY, "100", "1");
        UUID coin = UUID.randomUUID();
        Execution execution = new Execution(
                new BigDecimal("100"),
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("0.001"),
                coin,
                Liquidity.MAKER,
                FillSource.REPLAY,
                AT);

        PaperFill fill = PaperFill.of(order, execution);

        assertThat(fill.getOrderId()).isEqualTo(order.getId());
        assertThat(fill.getAccountId()).isEqualTo(ACCOUNT);
        assertThat(fill.getPairId()).isEqualTo(PAIR);
        assertThat(fill.getMarketType()).isEqualTo(MarketType.SPOT);
        assertThat(fill.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(fill.getFillPrice()).isEqualByComparingTo("100");
        assertThat(fill.getFillQuantity()).isEqualByComparingTo("1");
        assertThat(fill.getQuoteAmount()).isEqualByComparingTo("100");
        assertThat(fill.getFeeAmount()).isEqualByComparingTo("0.001");
        assertThat(fill.getFeeCoinId()).isEqualTo(coin);
        assertThat(fill.getLiquidity()).isEqualTo(Liquidity.MAKER);
        assertThat(fill.getFillSource()).isEqualTo(FillSource.REPLAY);
        assertThat(fill.getTradedAt()).isEqualTo(AT);
    }

    @Test
    void TR02_anExecutionOfNothingOrWithANegativeFee_isRefused() {
        UUID coin = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Execution(
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        coin,
                        Liquidity.TAKER,
                        FillSource.LIVE,
                        AT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Execution(
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        new BigDecimal("-0.1"),
                        coin,
                        Liquidity.TAKER,
                        FillSource.LIVE,
                        AT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Execution(
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        coin,
                        Liquidity.TAKER,
                        FillSource.LIVE,
                        AT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Execution(
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        coin,
                        Liquidity.TAKER,
                        FillSource.LIVE,
                        AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new Execution(
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        null,
                        Liquidity.TAKER,
                        FillSource.LIVE,
                        AT));
        assertThatNullPointerException().isThrownBy(() -> PaperFill.of(null, null));
    }

    private static PaperOrder limit(OrderSide side, String price, String quantity) {
        return PaperOrder.spotLimit(
                ACCOUNT, PAIR, "k", side, TimeInForce.GTC, new BigDecimal(price), new BigDecimal(quantity));
    }
}
