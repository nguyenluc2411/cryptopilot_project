package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final UUID OTHER_PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b002");
    /** The open time of the candle the test ranges belong to. */
    private static final Instant MINUTE = Instant.parse("2026-10-03T08:00:00Z");

    private static final Instant ACTIVATED = MINUTE.minusSeconds(3600);

    private final MatchingEngine engine = new MatchingEngine();

    @Test
    void NSF07_withNothingTracked_aRangeFillsNothing() {
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "1", "1000"))).isEmpty();
        assertThat(engine.size()).isZero();
    }

    @Test
    void BR33_aLongEntry_fillsWhenTheLowReachesIt_inclusive_andOnlyOnce() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        engine.track(entry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "100.01", "105")))
                .isEmpty();
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "100", "105"))).containsExactly(fill(entry));
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "105"))).isEmpty();
        assertThat(engine.size()).isZero();
    }

    @Test
    void BR33_aShortEntry_fillsWhenTheHighReachesIt_inclusive() {
        TrackedEntry entry = entry(MarketType.FUTURES, PAIR, Direction.SHORT, "100");
        engine.track(entry);

        assertThat(engine.onRange(range(MarketType.FUTURES, PAIR, "95", "99.99")))
                .isEmpty();
        assertThat(engine.onRange(range(MarketType.FUTURES, PAIR, "95", "100"))).containsExactly(fill(entry));
    }

    @Test
    void BR33_aCandleThatGapsThroughTheEntry_fillsAtTheEntryPrice() {
        TrackedEntry longEntry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry shortEntry = entry(MarketType.SPOT, OTHER_PAIR, Direction.SHORT, "100");
        engine.track(longEntry);
        engine.track(shortEntry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "95")))
                .singleElement()
                .satisfies(fill -> assertThat(fill.price()).isEqualByComparingTo("100"));
        assertThat(engine.onRange(range(MarketType.SPOT, OTHER_PAIR, "105", "110")))
                .singleElement()
                .satisfies(fill -> assertThat(fill.price()).isEqualByComparingTo("100"));
    }

    @Test
    void D78_aFill_isRecordedAtTheOpenTimeOfItsCandle_notAtTheUpdateTime() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        engine.track(entry);

        PriceRange update = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("101"), MINUTE, MINUTE.plusSeconds(37));

        assertThat(engine.onRange(update)).containsExactly(new Fill(entry.planId(), new BigDecimal("100"), MINUTE));
    }

    @Test
    void D77_aCandleThatOpenedBeforeTheActivation_doesNotFill_andTheEntryWaitsForTheNextCandle() {
        Instant activated = MINUTE.plusSeconds(30);
        TrackedEntry entry = new TrackedEntry(
                UUID.randomUUID(), MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100"), activated);
        engine.track(entry);

        PriceRange sameMinute = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("101"), MINUTE, MINUTE.plusSeconds(45));
        PriceRange nextMinute = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("101"), MINUTE.plusSeconds(60));

        assertThat(engine.onRange(sameMinute)).isEmpty();
        assertThat(engine.size()).isEqualTo(1);
        assertThat(engine.onRange(nextMinute))
                .containsExactly(new Fill(entry.planId(), new BigDecimal("100"), MINUTE.plusSeconds(60)));
    }

    @Test
    void D77_aCandleThatOpenedAtTheActivationInstant_fills() {
        TrackedEntry entry = new TrackedEntry(
                UUID.randomUUID(), MarketType.SPOT, PAIR, Direction.SHORT, new BigDecimal("100"), MINUTE);
        engine.track(entry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "99", "101"))).containsExactly(fill(entry));
    }

    @Test
    void D77_anEntryHeldBackByItsActivation_doesNotHoldBackOlderEntriesOfTheSameLevel() {
        TrackedEntry older = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry newer = new TrackedEntry(
                UUID.randomUUID(), MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100"), MINUTE.plusSeconds(1));
        engine.track(older);
        engine.track(newer);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "99", "101"))).containsExactly(fill(older));
        engine.untrack(newer.planId());
        assertThat(engine.size()).isZero();
    }

    @Test
    void NSF07_aWideRange_fillsBothSides() {
        TrackedEntry longEntry = entry(MarketType.FUTURES, PAIR, Direction.LONG, "98");
        TrackedEntry shortEntry = entry(MarketType.FUTURES, PAIR, Direction.SHORT, "102");
        engine.track(longEntry);
        engine.track(shortEntry);

        assertThat(engine.onRange(range(MarketType.FUTURES, PAIR, "97", "103")))
                .containsExactlyInAnyOrder(fill(longEntry), fill(shortEntry));
    }

    @Test
    void NSF07_aRange_fillsOnlyItsOwnMarketAndPair() {
        TrackedEntry spot = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry futures = entry(MarketType.FUTURES, PAIR, Direction.LONG, "100");
        TrackedEntry other = entry(MarketType.SPOT, OTHER_PAIR, Direction.LONG, "100");
        engine.track(spot);
        engine.track(futures);
        engine.track(other);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "110"))).containsExactly(fill(spot));
        assertThat(engine.size()).isEqualTo(2);
    }

    @Test
    void NSF07_anUntrackedPlan_neverFills() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry kept = entry(MarketType.SPOT, PAIR, Direction.SHORT, "100");
        engine.track(entry);
        engine.track(kept);

        engine.untrack(entry.planId());
        engine.untrack(UUID.randomUUID());

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "100"))).containsExactly(fill(kept));
    }

    @Test
    void NSF07_untrackingTheLastEntryOfAPair_dropsItsBooks() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        engine.track(entry);

        engine.untrack(entry.planId());

        assertThat(engine.size()).isZero();
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "110"))).isEmpty();
    }

    @Test
    void NSF07_trackingAPlanAgain_replacesItsEntry() {
        UUID planId = UUID.randomUUID();
        engine.track(new TrackedEntry(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100"), ACTIVATED));
        TrackedEntry moved =
                new TrackedEntry(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("90"), ACTIVATED);

        engine.track(moved);

        assertThat(engine.size()).isEqualTo(1);
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "95", "110"))).isEmpty();
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "110"))).containsExactly(fill(moved));
    }

    @Test
    void NSF07_aRangeThatFillsOneSide_keepsTheOther() {
        TrackedEntry longEntry = entry(MarketType.SPOT, PAIR, Direction.LONG, "98");
        TrackedEntry shortEntry = entry(MarketType.SPOT, PAIR, Direction.SHORT, "102");
        engine.track(longEntry);
        engine.track(shortEntry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "97", "100"))).containsExactly(fill(longEntry));
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "101", "102"))).containsExactly(fill(shortEntry));
    }

    private static TrackedEntry entry(MarketType market, UUID pair, Direction direction, String price) {
        return new TrackedEntry(UUID.randomUUID(), market, pair, direction, new BigDecimal(price), ACTIVATED);
    }

    private static PriceRange range(MarketType market, UUID pair, String low, String high) {
        return new PriceRange(market, pair, new BigDecimal(low), new BigDecimal(high), MINUTE);
    }

    /** The fill of an entry reached by a range of {@link #MINUTE}: the entry price at the candle's open time. */
    private static Fill fill(TrackedEntry entry) {
        return new Fill(entry.planId(), entry.entryPrice(), MINUTE);
    }
}
