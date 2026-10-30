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
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A paper order's life: what it locks, how it executes in parts, and that a finished one stays finished. */
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
    void TR06_anOrderExecutedInParts_isPartiallyFilled_locksOnlyItsRest_andAveragesItsPrices() {
        PaperOrder order = limit(OrderSide.BUY, "100", "3");

        order.fill(BigDecimal.ONE, new BigDecimal("98"), new BigDecimal("98"), AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.isOpen()).isTrue();
        assertThat(order.getClosedAt()).isNull();
        assertThat(order.remainingQuantity()).isEqualByComparingTo("2");
        assertThat(order.lockedAmount()).isEqualByComparingTo("200");
        assertThat(order.lockedAfter(new BigDecimal("2"))).isZero();

        order.fill(new BigDecimal("2"), new BigDecimal("99.5"), new BigDecimal("199"), AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getExecutedQuantity()).isEqualByComparingTo("3");
        assertThat(order.getCumQuoteAmount()).isEqualByComparingTo("297");
        assertThat(order.getAvgPrice()).as("(98 + 2 x 99.5) / 3").isEqualByComparingTo("99");
        assertThat(order.getClosedAt()).isEqualTo(AT);
    }

    @Test
    void TR06_moreThanAnOrderHasLeft_doesNotExecute() {
        PaperOrder order = limit(OrderSide.BUY, "100", "2");
        order.fill(BigDecimal.ONE, new BigDecimal("99"), new BigDecimal("99"), AT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> order.fill(new BigDecimal("1.5"), new BigDecimal("99"), new BigDecimal("148.5"), AT));
        assertThatIllegalArgumentException().isThrownBy(() -> order.lockedAfter(new BigDecimal("1.5")));
    }

    @Test
    void TR02_aBuyByTotal_executesTheQuantityItsAmountBuys_andIsFilledWhenCompleted() {
        PaperOrder order = PaperOrder.spotMarketFor(ACCOUNT, PAIR, "k", OrderSide.BUY, new BigDecimal("50"));
        assertThatIllegalStateException().isThrownBy(() -> order.completeFill(AT));

        order.fill(new BigDecimal("0.3"), new BigDecimal("100"), new BigDecimal("30"), AT);
        order.fill(new BigDecimal("0.2"), new BigDecimal("100"), new BigDecimal("20"), AT);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThatIllegalStateException().isThrownBy(order::remainingQuantity);
        order.completeFill(AT);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getOrigQuantity()).isNull();
        assertThat(order.getQuoteOrderAmount()).isEqualByComparingTo("50");
        assertThat(order.getExecutedQuantity()).isEqualByComparingTo("0.5");
    }

    @Test
    void QT7_onlyAFilledOrder_isFlaggedForTakingMoreThanTheDepthShown() {
        PaperOrder order = PaperOrder.spotMarket(ACCOUNT, PAIR, "k", OrderSide.SELL, BigDecimal.ONE);
        assertThatIllegalStateException().isThrownBy(order::flagDepthExhausted);

        order.fill(BigDecimal.ONE, new BigDecimal("99"), new BigDecimal("99"), AT);
        order.flagDepthExhausted();

        assertThat(order.getStatusReason()).isEqualTo(PaperOrder.DEPTH_EXHAUSTED);
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
        assertThat(fill.getUserId()).isEqualTo(ACCOUNT);
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

    /** TR-15: a Futures order has a position side, may be reduce-only, and only its fills belong to a position. */
    @Test
    void TR15_aFuturesOrder_hasAPositionSide_andOnlyItsFillsBelongToAPosition() {
        PaperOrder futures = PaperOrder.futuresLimit(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "f-1",
                OrderSide.SELL,
                PositionSide.BOTH,
                TimeInForce.GTC,
                new BigDecimal("100"),
                BigDecimal.ONE,
                true);
        PaperOrder spot =
                PaperOrder.spotMarket(UUID.randomUUID(), UUID.randomUUID(), "s-1", OrderSide.BUY, BigDecimal.ONE);
        PaperFill.Execution execution = new PaperFill.Execution(
                new BigDecimal("100"),
                BigDecimal.ONE,
                new BigDecimal("100"),
                BigDecimal.ZERO,
                UUID.randomUUID(),
                Liquidity.MAKER,
                FillSource.LIVE,
                Instant.now());
        UUID position = UUID.randomUUID();

        PaperFill fill = PaperFill.futures(futures, execution, position, new BigDecimal("3"));

        assertThat(futures.getMarketType()).isEqualTo(MarketType.FUTURES);
        assertThat(futures.isReduceOnly()).isTrue();
        assertThat(fill.getPositionId()).isEqualTo(position);
        assertThat(fill.getPositionSide()).isEqualTo(PositionSide.BOTH);
        assertThat(fill.getRealizedPnlAmount()).isEqualByComparingTo("3");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaperFill.futures(spot, execution, position, BigDecimal.ZERO));
    }

    @Test
    void TR25_aFuturesLimit_isAmendedInPlace_andWaitsAgainOnlyForANewPriceOrALargerQuantity() {
        PaperOrder order = futuresLimit("100", "2");

        assertThat(order.amend(new BigDecimal("100"), BigDecimal.ONE, AT)).isFalse();
        assertThat(order.getAmendedAt()).isNull();
        assertThat(order.amend(new BigDecimal("100"), new BigDecimal("3"), AT)).isTrue();
        assertThat(order.amend(new BigDecimal("99"), new BigDecimal("3"), AT.plusSeconds(1)))
                .isTrue();

        assertThat(order.getLimitPrice()).isEqualByComparingTo("99");
        assertThat(order.getOrigQuantity()).isEqualByComparingTo("3");
        assertThat(order.getAmendCount()).isEqualTo(3);
        assertThat(order.restingSince()).isEqualTo(AT.plusSeconds(1));
    }

    @Test
    void TR25_onlyAWorkingFuturesLimit_isAmended_toMoreThanWhatExecuted() {
        PaperOrder spot = limit(OrderSide.BUY, "100", "1");
        PaperOrder market =
                PaperOrder.futuresMarket(ACCOUNT, PAIR, "m", OrderSide.BUY, PositionSide.BOTH, BigDecimal.ONE, false);
        PaperOrder partly = futuresLimit("100", "2");
        partly.fill(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"), AT);
        PaperOrder ended = futuresLimit("100", "2");
        ended.cancel(AT);

        assertThatIllegalStateException().isThrownBy(() -> spot.amend(new BigDecimal("99"), BigDecimal.ONE, AT));
        assertThatIllegalStateException().isThrownBy(() -> market.amend(new BigDecimal("99"), BigDecimal.ONE, AT));
        assertThatIllegalStateException().isThrownBy(() -> ended.amend(new BigDecimal("99"), BigDecimal.ONE, AT));
        assertThatIllegalArgumentException().isThrownBy(() -> partly.amend(new BigDecimal("99"), BigDecimal.ONE, AT));
        assertThatIllegalArgumentException().isThrownBy(() -> partly.amend(BigDecimal.ZERO, new BigDecimal("3"), AT));
    }

    private static PaperOrder futuresLimit(String price, String quantity) {
        return PaperOrder.futuresLimit(
                ACCOUNT,
                PAIR,
                "f-" + UUID.randomUUID(),
                OrderSide.BUY,
                PositionSide.BOTH,
                TimeInForce.GTC,
                new BigDecimal(price),
                new BigDecimal(quantity),
                false);
    }

    @Test
    void TR44_aGtdOrder_carriesItsDate_andIsDueFromIt() {
        PaperOrder gtd = PaperOrder.futuresLimit(
                        ACCOUNT,
                        PAIR,
                        "g",
                        OrderSide.BUY,
                        PositionSide.BOTH,
                        TimeInForce.GTD,
                        new BigDecimal("100"),
                        BigDecimal.ONE,
                        false)
                .goodTill(AT);

        assertThat(gtd.getGoodTillDate()).isEqualTo(AT);
        assertThat(gtd.expiredBy(AT.minusMillis(1))).isFalse();
        assertThat(gtd.expiredBy(AT)).isTrue();
        assertThat(futuresLimit("100", "1").expiredBy(AT)).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> gtd.goodTill(AT));
        assertThatIllegalStateException()
                .isThrownBy(() -> futuresLimit("100", "1").goodTill(AT));
        gtd.cancel(AT);
        assertThat(gtd.expiredBy(AT)).isFalse();
        assertThat(TimeInForce.GTD.futuresOnly()).isTrue();
        assertThat(TimeInForce.GTX.futuresOnly()).isTrue();
        assertThat(TimeInForce.GTC.futuresOnly()).isFalse();
    }
}
