package com.cryptopilot.paper.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The trigger book of TR-09 and TR-11: stop limits triggered at or past their stop in the direction fixed at
 * placement, trailing stops activated, following their best price and triggered on the callback, and no price traded
 * before an order's placement counted (D-77).
 *
 * <p>Rule: TR-09, TR-11; D-77, D-78.
 */
class TriggerBookTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant PLACED = Instant.parse("2026-10-14T10:00:00Z");

    private final TriggerBook book = new TriggerBook();

    @Test
    void TR09_aStopBelow_triggersWhenThePriceTradesAtOrBelowIt_atThatTime() {
        TriggerOrder stop = stop(TriggerCondition.AT_OR_BELOW, "95");
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
        book.track(stop(TriggerCondition.AT_OR_ABOVE, "105"));

        assertThat(book.onRange(trades("90", "104.99", 1, 2))).isEmpty();
        assertThat(book.onRange(trades("100", "105", 3, 4))).hasSize(1);
    }

    @Test
    void D77_aPriceTradedBeforeThePlacement_neverTriggers() {
        book.track(stop(TriggerCondition.AT_OR_BELOW, "95"));

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
        TriggerOrder stop = stop(TriggerCondition.AT_OR_BELOW, "95");
        book.track(stop);
        book.untrack(stop.orderId());
        book.untrack(UUID.randomUUID());

        assertThat(book.onRange(trades("90", "100", 1, 2))).isEmpty();
        assertThat(book.size()).isZero();
    }

    @Test
    void TR11_aSellTrailingStop_followsTheHigh_andTriggersOnTheCallback() {
        // Active from its placement at 100, 2 % callback: the stop starts at 98.
        TriggerOrder trailing = trailing(TriggerCondition.AT_OR_BELOW, "2", null, "100");
        book.track(trailing);

        assertThat(book.onRange(trades("98.5", "110", 1, 2))).isEmpty();
        assertThat(book.movedExtremes()).containsExactly(Map.entry(trailing.orderId(), new BigDecimal("110")));
        // The stop is now 110 × 0.98 = 107.8.
        assertThat(book.onRange(trades("108", "109", 3, 4))).isEmpty();
        List<TriggerBook.Triggered> fired = book.onRange(trades("107.8", "108", 6, 5));

        assertThat(fired).singleElement().satisfies(triggered -> {
            assertThat(triggered.stopPrice()).isEqualByComparingTo("107.8");
            assertThat(triggered.at()).isEqualTo(PLACED.plusSeconds(6));
        });
        assertThat(book.movedExtremes()).isEmpty();
    }

    @Test
    void TR11_aBuyTrailingStop_waitsForItsActivation_thenFollowsTheLow() {
        TriggerOrder trailing = trailing(TriggerCondition.AT_OR_ABOVE, "1", "90", null);
        book.track(trailing);

        // Not activated above 90: a rise to 200 triggers nothing.
        assertThat(book.onRange(trades("91", "200", 1, 2))).isEmpty();
        // Activated at 89, its stop is 89 × 1.01 = 89.89.
        assertThat(book.onRange(trades("89", "89.5", 3, 4))).isEmpty();
        // It follows the low to 80: the stop is now 80.8.
        assertThat(book.onRange(trades("80", "80.5", 5, 6))).isEmpty();
        assertThat(book.onRange(trades("80.79", "80.79", 7, 7))).isEmpty();
        assertThat(book.onRange(trades("80.5", "80.8", 8, 9)))
                .singleElement()
                .satisfies(triggered -> assertThat(triggered.stopPrice()).isEqualByComparingTo("80.8"));
    }

    @Test
    void D78_aCandleAlone_neverTriggersATrailingStopOnAPullbackThatMayHavePrecededItsPeak() {
        book.track(trailing(TriggerCondition.AT_OR_BELOW, "2", null, "100"));

        // Low 90 and high 120 at the same instant: the high is taken last, so 90 is not a pullback from 120.
        PriceRange candle = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("120"), PLACED, false, FillSource.LIVE);

        assertThat(book.onRange(candle)).isEmpty();
        assertThat(book.movedExtremes()).containsValue(new BigDecimal("120"));
    }

    @Test
    void TR11_aRestoredBestPrice_isWhereATrailingStopResumes() {
        book.track(trailing(TriggerCondition.AT_OR_BELOW, "10", "50", "200"));

        // Restored at a best price of 200, the stop is 180, whatever the activation price.
        assertThat(book.onRange(trades("181", "190", 1, 2))).isEmpty();
        assertThat(book.onRange(trades("180", "185", 3, 4))).hasSize(1);
    }

    @Test
    void trailingStop_isTheBestPriceLessOrPlusTheCallback() {
        assertThat(TriggerBook.trailingStop(TriggerCondition.AT_OR_BELOW, new BigDecimal("200"), new BigDecimal("1.5")))
                .isEqualByComparingTo("197");
        assertThat(TriggerBook.trailingStop(TriggerCondition.AT_OR_ABOVE, new BigDecimal("200"), new BigDecimal("1.5")))
                .isEqualByComparingTo("203");
    }

    private static TriggerOrder stop(TriggerCondition condition, String stop) {
        return new TriggerOrder(
                UUID.randomUUID(),
                ACCOUNT,
                MarketType.SPOT,
                PAIR,
                condition,
                new BigDecimal(stop),
                null,
                null,
                null,
                PLACED);
    }

    private static TriggerOrder trailing(
            TriggerCondition condition, String callbackRate, String activation, String extreme) {
        return new TriggerOrder(
                UUID.randomUUID(),
                ACCOUNT,
                MarketType.SPOT,
                PAIR,
                condition,
                null,
                new BigDecimal(callbackRate),
                activation == null ? null : new BigDecimal(activation),
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
