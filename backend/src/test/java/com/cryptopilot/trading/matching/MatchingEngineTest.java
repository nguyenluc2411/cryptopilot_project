package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
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
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");

    private final MatchingEngine engine = new MatchingEngine();

    @Test
    void NSF07_withNothingTracked_aRangeFiresNothing() {
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "1", "1000"))).isEmpty();
        assertThat(engine.size()).isZero();
    }

    @Test
    void NSF07_aLongEntry_firesWhenTheLowReachesIt_andOnlyOnce() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        engine.track(entry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "100.01", "105")))
                .isEmpty();
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "100", "105"))).containsExactly(entry);
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "105"))).isEmpty();
        assertThat(engine.size()).isZero();
    }

    @Test
    void NSF07_aShortEntry_firesWhenTheHighReachesIt() {
        TrackedEntry entry = entry(MarketType.FUTURES, PAIR, Direction.SHORT, "100");
        engine.track(entry);

        assertThat(engine.onRange(range(MarketType.FUTURES, PAIR, "95", "99.99")))
                .isEmpty();
        assertThat(engine.onRange(range(MarketType.FUTURES, PAIR, "95", "100"))).containsExactly(entry);
    }

    @Test
    void NSF07_aWideRange_firesBothSides() {
        TrackedEntry longEntry = entry(MarketType.FUTURES, PAIR, Direction.LONG, "98");
        TrackedEntry shortEntry = entry(MarketType.FUTURES, PAIR, Direction.SHORT, "102");
        engine.track(longEntry);
        engine.track(shortEntry);

        assertThat(engine.onRange(range(MarketType.FUTURES, PAIR, "97", "103")))
                .containsExactlyInAnyOrder(longEntry, shortEntry);
    }

    @Test
    void NSF07_aRange_firesOnlyItsOwnMarketAndPair() {
        TrackedEntry spot = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry futures = entry(MarketType.FUTURES, PAIR, Direction.LONG, "100");
        TrackedEntry other = entry(MarketType.SPOT, OTHER_PAIR, Direction.LONG, "100");
        engine.track(spot);
        engine.track(futures);
        engine.track(other);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "110"))).containsExactly(spot);
        assertThat(engine.size()).isEqualTo(2);
    }

    @Test
    void NSF07_anUntrackedPlan_neverFires() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry kept = entry(MarketType.SPOT, PAIR, Direction.SHORT, "100");
        engine.track(entry);
        engine.track(kept);

        engine.untrack(entry.planId());
        engine.untrack(UUID.randomUUID());

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "100"))).containsExactly(kept);
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
        engine.track(new TrackedEntry(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100")));
        TrackedEntry moved = new TrackedEntry(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("90"));

        engine.track(moved);

        assertThat(engine.size()).isEqualTo(1);
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "95", "110"))).isEmpty();
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "110"))).containsExactly(moved);
    }

    @Test
    void NSF07_aRangeThatFiresOneSide_keepsTheOther() {
        TrackedEntry longEntry = entry(MarketType.SPOT, PAIR, Direction.LONG, "98");
        TrackedEntry shortEntry = entry(MarketType.SPOT, PAIR, Direction.SHORT, "102");
        engine.track(longEntry);
        engine.track(shortEntry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "97", "100"))).containsExactly(longEntry);
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "101", "102"))).containsExactly(shortEntry);
    }

    private static TrackedEntry entry(MarketType market, UUID pair, Direction direction, String price) {
        return new TrackedEntry(UUID.randomUUID(), market, pair, direction, new BigDecimal(price));
    }

    private static PriceRange range(MarketType market, UUID pair, String low, String high) {
        return new PriceRange(market, pair, new BigDecimal(low), new BigDecimal(high), AT);
    }
}
