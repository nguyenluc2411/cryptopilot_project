package com.cryptopilot.paper.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The trigger book of TR-09 and TR-11, as the exchange's Spot stops behave: a stop triggered at or past its stopPrice on
 * the side its type and side fix; a trailing stop tracking the peak (SELL) or trough (BUY) from the first trade, or from
 * the first trade that meets its stopPrice, and triggered on the delta; no price traded before the placement counted.
 *
 * <p>Rule: TR-09, TR-11; D-77, D-78.
 *
 * <p>Reference: Binance Spot API documentation, "Trailing Stop FAQ".
 */
class TriggerBookTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant PLACED = Instant.parse("2026-10-14T10:00:00Z");

    private final TriggerBook book = new TriggerBook();

    @Test
    void TR09_theStopCondition_isFixedByTheTypeAndTheSide_asOnTheExchange() {
        assertThat(OrderType.STOP_LOSS.triggerConditionFor(OrderSide.BUY)).isEqualTo(TriggerCondition.AT_OR_ABOVE);
        assertThat(OrderType.STOP_LOSS_LIMIT.triggerConditionFor(OrderSide.SELL))
                .isEqualTo(TriggerCondition.AT_OR_BELOW);
        assertThat(OrderType.TAKE_PROFIT.triggerConditionFor(OrderSide.SELL)).isEqualTo(TriggerCondition.AT_OR_ABOVE);
        assertThat(OrderType.TAKE_PROFIT_LIMIT.triggerConditionFor(OrderSide.BUY))
                .isEqualTo(TriggerCondition.AT_OR_BELOW);
    }

    @Test
    void TR09_aStopLossSell_triggersWhenThePriceTradesAtOrBelowItsStop_atThatTime() {
        TriggerOrder stop = stop(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "95");
        book.track(stop);

        assertThat(book.onRange(trades("96", "101", 1, 2))).isEmpty();
        List<TriggerBook.Triggered> fired = book.onRange(trades("95", "97", 5, 3));

        assertThat(fired).singleElement().satisfies(triggered -> {
            assertThat(triggered.order().orderId()).isEqualTo(stop.orderId());
            assertThat(triggered.at()).isEqualTo(PLACED.plusSeconds(5));
            assertThat(triggered.stopPrice()).isEqualByComparingTo("95");
        });
        assertThat(book.hasOrders(MarketType.SPOT, PAIR)).isFalse();
    }

    @Test
    void TR09_aStopAbove_triggersAtOrAboveIt_andNotBelow() {
        book.track(stop(OrderSide.BUY, TriggerCondition.AT_OR_ABOVE, "105"));

        assertThat(book.onRange(trades("90", "104.99", 1, 2))).isEmpty();
        assertThat(book.onRange(trades("100", "105", 3, 4))).hasSize(1);
    }

    @Test
    void D77_aPriceTradedBeforeThePlacement_neverTriggers() {
        book.track(stop(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "95"));

        assertThat(book.onRange(new PriceRange(
                        MarketType.SPOT,
                        PAIR,
                        new BigDecimal("90"),
                        new BigDecimal("100"),
                        PLACED.minusSeconds(30),
                        true,
                        FillSource.LIVE)))
                .isEmpty();
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    void untrack_removesTheOrder() {
        TriggerOrder stop = stop(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "95");
        book.track(stop);
        book.untrack(stop.orderId());
        book.untrack(UUID.randomUUID());

        assertThat(book.onRange(trades("90", "100", 1, 2))).isEmpty();
        assertThat(book.size()).isZero();
    }

    @Test
    void TR11_aSellTrailingStopWithoutAStopPrice_tracksFromTheNextTrade_andTriggersOnTheDelta() {
        // 2 % trailing delta (200 BIPS), no stopPrice: the first trade is the first peak.
        TriggerOrder trailing = trailing(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, null, "2", null);
        book.track(trailing);

        assertThat(book.onRange(trades("100", "100", 1, 1))).isEmpty();
        assertThat(book.onRange(trades("98.5", "110", 2, 3))).isEmpty();
        assertThat(book.movedExtremes()).containsExactly(Map.entry(trailing.orderId(), new BigDecimal("110")));
        // The trigger is now 110 × 0.98 = 107.8.
        assertThat(book.onRange(trades("108", "109", 4, 5))).isEmpty();
        List<TriggerBook.Triggered> fired = book.onRange(trades("107.8", "108", 7, 6));

        assertThat(fired).singleElement().satisfies(triggered -> {
            assertThat(triggered.stopPrice()).isEqualByComparingTo("107.8");
            assertThat(triggered.at()).isEqualTo(PLACED.plusSeconds(7));
        });
        assertThat(book.movedExtremes()).isEmpty();
    }

    @Test
    void TR11_aTrailingTakeProfitBuy_startsOnceItsStopIsMet_thenFollowsTheTrough() {
        // TAKE_PROFIT BUY: the stop condition is met at or below 90; then 1 % back up from the trough triggers it.
        book.track(trailing(OrderSide.BUY, TriggerCondition.AT_OR_BELOW, "90", "1", null));

        assertThat(book.onRange(trades("91", "200", 1, 2)))
                .as("not tracking above 90")
                .isEmpty();
        assertThat(book.onRange(trades("89", "89.5", 3, 4))).isEmpty();
        assertThat(book.onRange(trades("80", "80.5", 5, 6))).isEmpty();
        assertThat(book.onRange(trades("80.79", "80.79", 7, 7))).isEmpty();
        assertThat(book.onRange(trades("80.5", "80.8", 8, 9)))
                .singleElement()
                .satisfies(triggered -> assertThat(triggered.stopPrice()).isEqualByComparingTo("80.8"));
    }

    @Test
    void TR11_aTrailingStopLossSellWithAStopPrice_startsOnceThePriceFallsToIt() {
        // STOP_LOSS SELL: tracking starts at or below 95, then 2 % back down from the peak since then triggers it.
        book.track(trailing(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "95", "2", null));

        assertThat(book.onRange(trades("96", "120", 1, 2)))
                .as("a rise above 95 is not tracked")
                .isEmpty();
        assertThat(book.onRange(trades("94", "94", 3, 3))).isEmpty();
        assertThat(book.onRange(trades("94.5", "100", 4, 5))).isEmpty();
        assertThat(book.onRange(trades("98", "98", 6, 6)))
                .singleElement()
                .satisfies(triggered -> assertThat(triggered.stopPrice()).isEqualByComparingTo("98"));
    }

    @Test
    void D78_aCandleAlone_neverTriggersATrailingStopOnAPullbackThatMayHavePrecededItsPeak() {
        book.track(trailing(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, null, "2", "100"));

        // Low 99 and high 120 at the same instant: the high is taken last, so 99 is not a pullback from 120.
        PriceRange candle = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("120"), PLACED, false, FillSource.LIVE);

        assertThat(book.onRange(candle)).isEmpty();
        assertThat(book.movedExtremes()).containsValue(new BigDecimal("120"));
    }

    @Test
    void TR11_aRestoredBestPrice_isWhereATrailingStopResumes() {
        book.track(trailing(OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "50", "10", "200"));

        // Restored at a best price of 200, it triggers at 180, whatever its stopPrice.
        assertThat(book.onRange(trades("181", "190", 1, 2))).isEmpty();
        assertThat(book.onRange(trades("180", "185", 3, 4))).hasSize(1);
    }

    @Test
    void trailingStop_isTheBestPriceLessOrPlusTheDelta() {
        assertThat(TriggerBook.trailingStop(OrderSide.SELL, new BigDecimal("200"), new BigDecimal("1.5")))
                .isEqualByComparingTo("197");
        assertThat(TriggerBook.trailingStop(OrderSide.BUY, new BigDecimal("200"), new BigDecimal("1.5")))
                .isEqualByComparingTo("203");
    }

    private static TriggerOrder stop(OrderSide side, TriggerCondition condition, String stop) {
        return new TriggerOrder(
                UUID.randomUUID(),
                ACCOUNT,
                MarketType.SPOT,
                PAIR,
                side,
                condition,
                new BigDecimal(stop),
                null,
                null,
                PLACED);
    }

    private static TriggerOrder trailing(
            OrderSide side, TriggerCondition condition, String stop, String percent, String extreme) {
        return new TriggerOrder(
                UUID.randomUUID(),
                ACCOUNT,
                MarketType.SPOT,
                PAIR,
                side,
                condition,
                stop == null ? null : new BigDecimal(stop),
                new BigDecimal(percent),
                extreme == null ? null : new BigDecimal(extreme),
                PLACED);
    }

    /** Trades whose low traded {@code lowSecond} and whose high {@code highSecond} seconds after the placement. */
    private static PriceRange trades(String low, String high, int lowSecond, int highSecond) {
        Instant lowAt = PLACED.plusSeconds(lowSecond);
        Instant highAt = PLACED.plusSeconds(highSecond);
        return new PriceRange(
                MarketType.SPOT,
                PAIR,
                new BigDecimal(low),
                new BigDecimal(high),
                lowAt.isBefore(highAt) ? lowAt : highAt,
                false,
                FillSource.LIVE,
                lowAt,
                highAt,
                new TreeMap<>(),
                new TreeMap<>());
    }
}
