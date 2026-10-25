package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TP-E: a change of an open position's levels acts from its instant. A range of a candle that opened before the change
 * is decided by the levels in force when that candle opened, a range of a later candle by the new ones; so live
 * updates and the same candles replayed by an engine rebuilt from the journal and its history give the same exit.
 *
 * <p>Rule: BR-35, NSF-07; D-77, D-78, D-79, D-124.
 */
class MatchingEngineLevelChangeTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c101");
    private static final UUID POSITION = UUID.fromString("019b76da-a800-7000-8000-00000000c102");
    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-00000000c103");
    private static final Instant M0 = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant ENTRY = M0.minus(Duration.ofHours(1));

    /** In the middle of minute 2. */
    private static final Instant CHANGED_AT = minute(2).plusSeconds(30);

    private final MatchingEngine engine = new MatchingEngine();

    /** LONG at 100, stop 95 → 98, take profit 110 → 104, changed in the middle of minute 2. */
    @Test
    void D124_aCandleOpenedAfterTheChange_isDecidedByTheNewLevels() {
        engine.open(position(0, "95", "110"));
        engine.changeLevels(position(1, "98", "104"), change("95", "110", "98", "104", CHANGED_AT));

        List<PlanOutcome> outcomes = engine.onRange(last(3, "100", "97.5", "101"));

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            PositionExit exit = (PositionExit) outcome;
            assertThat(exit.exit().reason()).isEqualTo(CloseReason.STOP_LOSS);
            assertThat(exit.exit().price()).isEqualByComparingTo("98");
            assertThat(exit.position().version())
                    .as("closed at the row's new version")
                    .isEqualTo(1L);
        });
    }

    /**
     * D-77 analogue: the rest of the minute the change was made in is decided by the earlier levels, which a later
     * update of that minute can still reach, while the new ones it reaches do not act on it.
     */
    @Test
    void D124_theCandleTheChangeWasMadeIn_isDecidedByTheEarlierLevels() {
        engine.open(position(0, "95", "110"));
        engine.changeLevels(position(1, "98", "104"), change("95", "110", "98", "104", CHANGED_AT));

        assertThat(engine.onRange(last(2, "100", "97.5", "105")))
                .as("the new stop 98 and take profit 104 are reached, the earlier 95 and 110 are not")
                .isEmpty();
        List<PlanOutcome> outcomes = engine.onRange(last(2, "100", "94", "105"));

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            PositionExit exit = (PositionExit) outcome;
            assertThat(exit.exit().reason()).isEqualTo(CloseReason.STOP_LOSS);
            assertThat(exit.exit().price()).as("the earlier stop").isEqualByComparingTo("95");
            assertThat(exit.exit().exitedAt()).isEqualTo(minute(2));
        });
    }

    /** A change made exactly at a candle's open acts on that candle, as an activation at the open may fill (D-77). */
    @Test
    void D124_aChangeAtTheCandlesOpen_actsOnThatCandle() {
        engine.open(position(0, "95", "110"));
        engine.changeLevels(position(1, "98", "104"), change("95", "110", "98", "104", minute(2)));

        assertThat(engine.onRange(last(2, "100", "99", "104"))).singleElement().satisfies(outcome -> assertThat(
                        ((PositionExit) outcome).exit().reason())
                .isEqualTo(CloseReason.TAKE_PROFIT));
    }

    /**
     * Q-80: {@code effectiveFrom} names the first candle the engine decides by the new levels: the next minute after a
     * change in the middle of one, the minute itself after a change at its open.
     */
    @Test
    void D124_Q80_effectiveFrom_isTheFirstCandleTheEngineUsesTheNewLevelsOn() {
        PositionLevelChange midMinute = change("95", "110", "98", "110", CHANGED_AT);
        PositionLevelChange atOpen = change("95", "110", "98", "110", minute(2));
        assertThat(midMinute.effectiveFrom()).isEqualTo(minute(3));
        assertThat(atOpen.effectiveFrom()).isEqualTo(minute(2));

        MatchingEngine before = rebuilt(position(1, "98", "110"), midMinute);
        assertThat(before.onRange(last(2, "100", "97.9", "101")))
                .as("the minute before effectiveFrom: still the stop 95")
                .isEmpty();
        MatchingEngine from = rebuilt(position(1, "98", "110"), midMinute);
        assertThat(from.onRange(last(3, "100", "97.9", "101")))
                .as("the minute at effectiveFrom: the stop 98")
                .hasSize(1);
    }

    /** Two changes: a candle between them is decided by the middle levels, an older one by the first. */
    @Test
    void D124_twoChanges_eachCandleGetsTheLevelsInForceWhenItOpened() {
        Instant first = minute(2).plusSeconds(10);
        Instant second = minute(4).plusSeconds(10);
        PositionLevelChange tighten = change("95", "110", "98", "110", first);
        PositionLevelChange widen = change("98", "110", "92", "110", second);

        MatchingEngine middle = rebuilt(position(2, "92", "110"), tighten, widen);
        assertThat(middle.onRange(last(3, "100", "97", "101")))
                .as("minute 3: the stop is 98")
                .singleElement()
                .satisfies(outcome ->
                        assertThat(((PositionExit) outcome).exit().price()).isEqualByComparingTo("98"));

        MatchingEngine older = rebuilt(position(2, "92", "110"), widen, tighten);
        assertThat(older.onRange(last(1, "100", "97", "101")))
                .as("minute 1: the stop is still 95; the history is kept oldest first whatever order it arrives in")
                .isEmpty();
        assertThat(older.onRange(last(1, "100", "95", "101"))).singleElement().satisfies(outcome -> assertThat(
                        ((PositionExit) outcome).exit().price())
                .isEqualByComparingTo("95"));

        MatchingEngine later = rebuilt(position(2, "92", "110"), tighten, widen);
        assertThat(later.onRange(last(5, "100", "93", "101")))
                .as("minute 5: the stop is 92")
                .isEmpty();
    }

    /**
     * Replay equals live: one recording of updates, with a change in the middle of minute 2, run live (the change
     * arrives between two updates of minute 2), then replayed as closed candles by an engine rebuilt from the journal's
     * current levels and its history after a crash. Both decide the same exit at the same candle and price.
     */
    @Test
    void D124_replayedCandles_decideTheSameExitAsLive_withAChangeInTheMiddle() {
        PositionLevelChange tighten = change("95", "110", "99", "110", CHANGED_AT);
        List<PriceRange> minuteTwoBefore = List.of(last(2, "100", "99.5", "100.5"));
        List<PriceRange> minuteTwoAfter = List.of(last(2, "100", "98.6", "100.5"));
        List<PriceRange> later = List.of(last(3, "100", "99.5", "101"), last(4, "100", "98.9", "101"));

        // Live: updates before the change, the change, then the rest.
        MatchingEngine live = new MatchingEngine();
        live.open(position(0, "95", "110"));
        List<PlanOutcome> liveOutcomes = new ArrayList<>();
        minuteTwoBefore.forEach(range -> liveOutcomes.addAll(live.onRange(range)));
        live.changeLevels(position(1, "99", "110"), tighten);
        minuteTwoAfter.forEach(range -> liveOutcomes.addAll(live.onRange(range)));
        later.forEach(range -> liveOutcomes.addAll(live.onRange(range)));

        // Replay: the crash lost minutes 2 to 4; the restart reads the current levels and the history.
        MatchingEngine replay = rebuilt(position(1, "99", "110"), tighten);
        List<PlanOutcome> replayOutcomes = new ArrayList<>();
        replayOutcomes.addAll(replay.onRange(closed(2, "100", "98.6", "100.5")));
        replayOutcomes.addAll(replay.onRange(closed(3, "100", "99.5", "101")));
        replayOutcomes.addAll(replay.onRange(closed(4, "100", "98.9", "101")));

        assertThat(liveOutcomes)
                .as("minute 2 (98.6) reaches the new stop 99 but opened before the change; minute 4 stops it at 99")
                .singleElement()
                .satisfies(outcome -> {
                    PositionExit exit = (PositionExit) outcome;
                    assertThat(exit.exit().reason()).isEqualTo(CloseReason.STOP_LOSS);
                    assertThat(exit.exit().price()).isEqualByComparingTo("99");
                    assertThat(exit.exit().exitedAt()).isEqualTo(minute(4));
                });
        assertThat(replayOutcomes).usingRecursiveComparison().isEqualTo(liveOutcomes);
    }

    /** An exit or a close forgets the history, so a later position of the same id starts without it. */
    @Test
    void D124_anExitOrACloseForgetsTheHistory() {
        engine.open(position(0, "95", "110"));
        engine.changeLevels(position(1, "98", "110"), change("95", "110", "98", "110", CHANGED_AT));
        engine.close(POSITION);
        engine.open(position(1, "98", "110"));
        assertThat(engine.onRange(last(2, "100", "97.9", "101")))
                .as("no earlier levels left: minute 2 is decided by the stop 98")
                .hasSize(1);

        engine.open(position(0, "95", "110"));
        engine.changeLevels(position(1, "98", "110"), change("95", "110", "98", "110", CHANGED_AT));
        assertThat(engine.onRange(last(3, "100", "97", "101"))).hasSize(1);
        engine.open(position(1, "98", "110"));
        assertThat(engine.onRange(last(2, "100", "97.9", "101")))
                .as("the exit forgot the history too")
                .hasSize(1);
    }

    /** A change leaves the liquidation in the mark books as it was, and other positions of the pair untouched. */
    @Test
    void D124_aChange_leavesTheLiquidationAndTheOtherPositionsAlone() {
        TrackedPosition other = new TrackedPosition(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("97"),
                new BigDecimal("120"),
                null,
                0L);
        engine.open(other);
        engine.open(position(0, "95", "110", "90"));
        engine.changeLevels(position(1, "96", "110", "90"), change("95", "110", "96", "110", CHANGED_AT));

        List<PlanOutcome> sameMinute = engine.onRange(last(2, "100", "96.5", "101"));
        assertThat(sameMinute).extracting(PlanOutcome::planId).containsExactly(other.planId());
        assertThat(engine.onRange(PriceRange.mark(
                        PAIR,
                        new BigDecimal("89"),
                        new BigDecimal("101"),
                        minute(2),
                        minute(2).plusSeconds(1),
                        false)))
                .singleElement()
                .satisfies(outcome ->
                        assertThat(((PositionExit) outcome).exit().reason()).isEqualTo(CloseReason.LIQUIDATION));
    }

    /** A position without a take profit: the change and the earlier levels work on its stop alone. */
    @Test
    void D124_aPositionWithoutATakeProfit_isDecidedByItsStopAlone() {
        TrackedPosition open = new TrackedPosition(
                POSITION,
                PLAN,
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("95"),
                null,
                null,
                0L);
        TrackedPosition changed = new TrackedPosition(
                POSITION,
                PLAN,
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("98"),
                null,
                null,
                1L);
        engine.open(open);
        engine.changeLevels(
                changed,
                new PositionLevelChange(POSITION, CHANGED_AT, new BigDecimal("95"), null, new BigDecimal("98"), null));

        assertThat(engine.onRange(last(2, "100", "97", "200")))
                .as("minute 2 still has the stop 95")
                .isEmpty();
        assertThat(engine.onRange(last(3, "100", "97.5", "101")))
                .singleElement()
                .satisfies(outcome ->
                        assertThat(((PositionExit) outcome).exit().price()).isEqualByComparingTo("98"));
    }

    private static MatchingEngine rebuilt(TrackedPosition current, PositionLevelChange... history) {
        MatchingEngine engine = new MatchingEngine();
        engine.open(current);
        for (PositionLevelChange change : history) {
            engine.recordLevelChange(change);
        }
        return engine;
    }

    private static TrackedPosition position(long version, String stop, String target) {
        return position(version, stop, target, null);
    }

    private static TrackedPosition position(long version, String stop, String target, String liquidation) {
        return new TrackedPosition(
                POSITION,
                PLAN,
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal(stop),
                new BigDecimal(target),
                liquidation == null ? null : new BigDecimal(liquidation),
                version);
    }

    private static PositionLevelChange change(
            String oldStop, String oldTarget, String newStop, String newTarget, Instant at) {
        return new PositionLevelChange(
                POSITION,
                at,
                new BigDecimal(oldStop),
                new BigDecimal(oldTarget),
                new BigDecimal(newStop),
                new BigDecimal(newTarget));
    }

    private static PriceRange last(int minute, String open, String low, String high) {
        return new PriceRange(
                MarketType.FUTURES,
                PAIR,
                new BigDecimal(open),
                new BigDecimal(low),
                new BigDecimal(high),
                minute(minute),
                minute(minute).plusSeconds(55),
                false);
    }

    private static PriceRange closed(int minute, String open, String low, String high) {
        return new PriceRange(
                MarketType.FUTURES,
                PAIR,
                new BigDecimal(open),
                new BigDecimal(low),
                new BigDecimal(high),
                minute(minute),
                minute(minute).plusSeconds(59),
                true);
    }

    private static Instant minute(int n) {
        return M0.plus(Duration.ofMinutes(n));
    }
}
