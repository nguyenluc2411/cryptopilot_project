package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The exit books and the mark price books of a partition: stop loss and take profit on the last price, liquidation on
 * the mark price, kept apart from the entry books; the candle the entry filled in; the excursions.
 *
 * <p>Rule: NSF-07, BR-35; TECHNICAL_DESIGN 7.7; D-77, D-78; D-112 R5; Q-59, Q-61.
 */
class MatchingEngineExitTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c001");
    private static final UUID OTHER_PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c002");
    private static final Instant MINUTE = Instant.parse("2026-10-03T08:00:00Z");
    private static final Instant EARLIER = MINUTE.minusSeconds(3600);

    private final MatchingEngine engine = new MatchingEngine();

    @Test
    void BR35_aLongPosition_exitsAtItsStopLossOrItsTakeProfit_once() {
        TrackedPosition stopped = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        TrackedPosition target = position(Direction.LONG, "100", EARLIER, "90", "110", null);
        engine.open(stopped);
        engine.open(target);

        List<PlanOutcome> outcomes = engine.onRange(last("100", "94.5", "111"));

        assertThat(outcomes).hasSize(2);
        assertExit(outcomes, stopped, CloseReason.STOP_LOSS, "95");
        assertExit(outcomes, target, CloseReason.TAKE_PROFIT, "110");
        assertThat(engine.positionCount()).isZero();
        assertThat(engine.onRange(last("100", "1", "1000"))).isEmpty();
    }

    @Test
    void BR35_aShortPosition_exitsAtTheMirroredLevels() {
        TrackedPosition stopped = position(Direction.SHORT, "100", EARLIER, "105", "85", null);
        TrackedPosition target = position(Direction.SHORT, "100", EARLIER, "110", "90", null);
        engine.open(stopped);
        engine.open(target);

        List<PlanOutcome> outcomes = engine.onRange(last("100", "89", "105.5"));

        assertExit(outcomes, stopped, CloseReason.STOP_LOSS, "105");
        assertExit(outcomes, target, CloseReason.TAKE_PROFIT, "90");
    }

    /** Q-61: an open beyond the take profit decides for it; otherwise SL first when one range reaches both. */
    @Test
    void Q61_theOpenDecides_elseTheStopLossGoesFirst_longAndShort() {
        TrackedPosition gapLong = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        TrackedPosition gapShort = position(Direction.SHORT, "100", EARLIER, "105", "85", null);
        engine.open(gapLong);
        assertExit(engine.onRange(last("118", "94", "120")), gapLong, CloseReason.TAKE_PROFIT, "115");
        engine.open(gapShort);
        assertExit(engine.onRange(last("83", "80", "106")), gapShort, CloseReason.TAKE_PROFIT, "85");

        TrackedPosition both = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        TrackedPosition bothShort = position(Direction.SHORT, "100", EARLIER, "105", "85", null);
        engine.open(both);
        engine.open(bothShort);
        List<PlanOutcome> inside = engine.onRange(last("100", "80", "120"));
        assertExit(inside, both, CloseReason.STOP_LOSS, "95");
        assertExit(inside, bothShort, CloseReason.STOP_LOSS, "105");
    }

    @Test
    void BR35_aMarkRange_liquidates_andALastRange_never() {
        TrackedPosition futures = position(Direction.LONG, "100", EARLIER, "80", "150", "90.37");
        engine.open(futures);

        assertThat(engine.onRange(last("100", "85", "101")))
                .as("the last price never liquidates; the stop loss 80 is not reached either")
                .isEmpty();
        List<PlanOutcome> outcomes = engine.onRange(marks("90", "95"));

        assertExit(outcomes, futures, CloseReason.LIQUIDATION, "90.37");
        assertThat(engine.positionCount()).isZero();
        assertThat(engine.onRange(last("100", "1", "1000")))
                .as("all its levels left")
                .isEmpty();
    }

    @Test
    void BR35_aShortIsLiquidatedWhenTheMarkRisesToItsPrice() {
        TrackedPosition futures = position(Direction.SHORT, "100", EARLIER, "120", "50", "109.56");
        engine.open(futures);

        assertThat(engine.onRange(marks("100", "109.55"))).isEmpty();
        assertExit(engine.onRange(marks("100", "109.56")), futures, CloseReason.LIQUIDATION, "109.56");
    }

    /** The #70 review: the books of plans and of positions are apart, so neither book returns an id of the other. */
    @Test
    void NSF07_aWaitingPlanAndAnOpenPositionAtTheSameLevel_areBothDecided() {
        TrackedEntry waiting = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.LONG, new BigDecimal("95"), EARLIER);
        TrackedPosition open = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        engine.track(waiting);
        engine.open(open);

        List<PlanOutcome> outcomes = engine.onRange(last("96", "95", "96"));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).filteredOn(Fill.class::isInstance).singleElement().satisfies(fill -> assertThat(
                        fill.planId())
                .isEqualTo(waiting.planId()));
        assertExit(outcomes, open, CloseReason.STOP_LOSS, "95");
    }

    @Test
    void NSF07_aRange_exitsOnlyThePositionsOfItsOwnMarketAndPair() {
        TrackedPosition elsewhere = new TrackedPosition(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                OTHER_PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                EARLIER,
                new BigDecimal("95"),
                new BigDecimal("115"),
                null,
                0);
        engine.open(elsewhere);

        assertThat(engine.onRange(last("100", "1", "1000"))).isEmpty();
        assertThat(engine.onRange(new PriceRange(
                        MarketType.SPOT,
                        OTHER_PAIR,
                        null,
                        BigDecimal.ONE,
                        new BigDecimal("1000"),
                        MINUTE,
                        MINUTE.plusSeconds(1),
                        false)))
                .isEmpty();
        assertThat(engine.positionCount()).isEqualTo(1);
    }

    /** TP-E: opening a position again replaces its levels and keeps its excursions. */
    @Test
    void D119_openingAPositionAgain_replacesItsLevels_andKeepsItsExcursions() {
        TrackedPosition first = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        engine.open(first);
        engine.onRange(last("100", "97", "103"));
        TrackedPosition moved = new TrackedPosition(
                first.positionId(),
                first.planId(),
                first.userId(),
                first.market(),
                first.pairId(),
                first.direction(),
                first.entryPrice(),
                first.entryTime(),
                new BigDecimal("98"),
                new BigDecimal("120"),
                null,
                1);
        engine.open(moved);

        assertThat(engine.excursion(first.positionId()))
                .contains(new Excursion(new BigDecimal("97"), new BigDecimal("103")));
        // The old levels left the books: the new stop loss 98 decides, not the old 95.
        assertExit(engine.onRange(last("100", "96", "116")), moved, CloseReason.STOP_LOSS, "98");
        assertThat(engine.positionCount()).isZero();
    }

    @Test
    void NSF07_aClosedPosition_leavesEveryBook_andUnknownPositionsAreIgnored() {
        TrackedPosition futures = position(Direction.LONG, "100", EARLIER, "95", "115", "90");
        engine.open(futures);

        engine.close(futures.positionId());
        engine.close(UUID.randomUUID());

        assertThat(engine.onRange(last("100", "1", "1000"))).isEmpty();
        assertThat(engine.onRange(marks("1", "1"))).isEmpty();
        assertThat(engine.excursion(futures.positionId())).isEmpty();
    }

    /** Q-59: the candle the entry filled in takes no profit; the levels go back and the next candle can. */
    @Test
    void Q59_theEntryCandle_takesNoProfit_andTheNextCandleDoes() {
        TrackedPosition fresh = position(Direction.LONG, "100", MINUTE, "95", "110", null);
        engine.open(fresh);

        assertThat(engine.onRange(last("100", "99", "111"))).isEmpty();
        assertExit(
                engine.onRange(new PriceRange(
                        MarketType.FUTURES,
                        PAIR,
                        new BigDecimal("105"),
                        new BigDecimal("104"),
                        new BigDecimal("111"),
                        MINUTE.plusSeconds(60),
                        MINUTE.plusSeconds(61),
                        false)),
                fresh,
                CloseReason.TAKE_PROFIT,
                "110");
    }

    @Test
    void Q59_aCandleThatEndedBeforeTheEntry_actsOnNothing_andItsLevelsGoBack() {
        TrackedPosition later = position(Direction.LONG, "100", MINUTE.plusSeconds(60), "95", "110", "90");
        engine.open(later);

        assertThat(engine.onRange(last("100", "80", "120"))).isEmpty();
        assertThat(engine.onRange(marks("80", "80"))).isEmpty();
        assertThat(engine.positionCount()).isEqualTo(1);
        assertThat(engine.excursion(later.positionId())).contains(Excursion.at(new BigDecimal("100")));
    }

    /**
     * Q-59, D-112 R5: a STOP filled at the candle's open is opened with that candle, which reaches its stop loss; the
     * excursion keeps only the adverse extreme, clamped at the exit.
     */
    @Test
    void Q59_aStopFilledAtTheOpen_exitsByItsStopLossInTheSameCandle() {
        TrackedPosition stop = position(Direction.LONG, "105.1", MINUTE, "100", "130", null);

        List<PlanOutcome> outcomes = engine.openAfterFill(stop, last("105", "99", "125"));

        PositionExit exit = assertExit(outcomes, stop, CloseReason.STOP_LOSS, "100");
        assertThat(exit.excursion()).isEqualTo(new Excursion(new BigDecimal("100"), new BigDecimal("105.1")));
        assertThat(engine.positionCount()).isZero();
    }

    /**
     * Q-59, D-123 safety net: a SHORT position opened already above its stop loss (activation refuses it; a replay edge
     * case can still produce it) exits at its entry price at once.
     */
    @Test
    void Q59_aLimitFilledAtActivationBeyondItsStop_exitsAtItsEntryPrice() {
        TrackedPosition limit = position(Direction.SHORT, "105.9", MINUTE.plusSeconds(30), "105", "90", null);

        PositionExit exit = assertExit(
                engine.openAfterFill(limit, last("103", "103", "106")), limit, CloseReason.STOP_LOSS, "105.9");

        assertThat(exit.excursion()).isEqualTo(Excursion.at(new BigDecimal("105.9")));
    }

    /** openAfterFill acts on the new position only: an older one keeps waiting for its own decision. */
    @Test
    void Q59_openAfterFill_appliesTheRangeToTheNewPositionOnly() {
        TrackedPosition older = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        engine.open(older);
        TrackedPosition fresh = position(Direction.LONG, "100", MINUTE, "99", "115", null);

        List<PlanOutcome> outcomes = engine.openAfterFill(fresh, last("100", "94", "101"));

        assertExit(outcomes, fresh, CloseReason.STOP_LOSS, "99");
        assertThat(outcomes).hasSize(1);
        assertThat(engine.excursion(older.positionId())).contains(Excursion.at(new BigDecimal("100")));
        assertExit(engine.onRange(last("100", "94", "101")), older, CloseReason.STOP_LOSS, "95");
    }

    /** D-112 R5: every range widens the excursions; the entry candle adds only its adverse extreme. */
    @Test
    void D112R5_theExcursions_followTheRanges_andTheEntryCandleAddsOnlyItsAdverseExtreme() {
        TrackedPosition fresh = position(Direction.SHORT, "100", MINUTE, "120", "50", null);
        engine.open(fresh);

        engine.onRange(last("100", "96", "104"));
        assertThat(engine.excursion(fresh.positionId()))
                .contains(new Excursion(new BigDecimal("104"), new BigDecimal("100")));

        engine.onRange(new PriceRange(
                MarketType.FUTURES,
                PAIR,
                new BigDecimal("101"),
                new BigDecimal("95"),
                new BigDecimal("103"),
                MINUTE.plusSeconds(60),
                MINUTE.plusSeconds(61),
                false));
        assertThat(engine.excursion(fresh.positionId()))
                .contains(new Excursion(new BigDecimal("104"), new BigDecimal("95")));
    }

    /** D-112 R5: the exit candle is clamped at the exit price on the side of the exit. */
    @Test
    void D112R5_theExitCandle_isClampedAtTheExitPrice() {
        TrackedPosition target = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        engine.open(target);
        engine.onRange(last("100", "97", "105"));

        PositionExit exit =
                assertExit(engine.onRange(last("110", "98", "116")), target, CloseReason.TAKE_PROFIT, "115");

        assertThat(exit.excursion()).isEqualTo(new Excursion(new BigDecimal("97"), new BigDecimal("115")));
    }

    /** A liquidation keeps the excursions of the last price ranges before it. */
    @Test
    void D112R5_aLiquidation_keepsTheLastPriceExcursions() {
        TrackedPosition futures = position(Direction.LONG, "100", EARLIER, "50", "150", "90.37");
        engine.open(futures);
        engine.onRange(last("100", "92", "101"));

        PositionExit exit = assertExit(engine.onRange(marks("90", "91")), futures, CloseReason.LIQUIDATION, "90.37");

        assertThat(exit.excursion()).isEqualTo(new Excursion(new BigDecimal("92"), new BigDecimal("101")));
    }

    @Test
    void BR35_aPositionWithoutLevels_waitsInNoBook() {
        TrackedPosition bare = position(Direction.LONG, "100", EARLIER, null, null, null);
        engine.open(bare);

        assertThat(engine.onRange(last("100", "1", "1000"))).isEmpty();
        assertThat(engine.onRange(marks("1", "1"))).isEmpty();
        assertThat(engine.positionCount()).isEqualTo(1);
        assertThat(engine.excursion(bare.positionId())).contains(new Excursion(BigDecimal.ONE, new BigDecimal("1000")));
    }

    @Test
    void BR68_aStopEntry_stillFillsBesideTheExitBooks() {
        TrackedEntry stop = TrackedEntries.of(
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                EntryType.STOP,
                new BigDecimal("105"),
                new BigDecimal("90"),
                new BigDecimal("130"),
                null,
                EARLIER);
        engine.track(stop);
        engine.open(position(Direction.LONG, "100", EARLIER, "90", "130", null));

        assertThat(engine.onRange(last("100", "100", "106"))).singleElement().isInstanceOf(Fill.class);
    }

    private static PositionExit assertExit(
            List<PlanOutcome> outcomes, TrackedPosition position, CloseReason reason, String price) {
        PositionExit exit = outcomes.stream()
                .filter(PositionExit.class::isInstance)
                .map(PositionExit.class::cast)
                .filter(candidate -> candidate.position().positionId().equals(position.positionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no exit of " + position.positionId() + " in " + outcomes));
        assertThat(exit.exit().reason()).isEqualTo(reason);
        assertThat(exit.exit().price()).isEqualByComparingTo(price);
        assertThat(exit.planId()).isEqualTo(position.planId());
        assertThat(exit.at()).isEqualTo(exit.exit().exitedAt());
        return exit;
    }

    private static TrackedPosition position(
            Direction direction, String entry, Instant entryTime, String stop, String target, String liquidation) {
        return new TrackedPosition(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                direction,
                new BigDecimal(entry),
                entryTime,
                stop == null ? null : new BigDecimal(stop),
                target == null ? null : new BigDecimal(target),
                liquidation == null ? null : new BigDecimal(liquidation),
                0);
    }

    private static PriceRange last(String open, String low, String high) {
        return new PriceRange(
                MarketType.FUTURES,
                PAIR,
                new BigDecimal(open),
                new BigDecimal(low),
                new BigDecimal(high),
                MINUTE,
                MINUTE.plusSeconds(1),
                false);
    }

    private static PriceRange marks(String low, String high) {
        return PriceRange.mark(PAIR, new BigDecimal(low), new BigDecimal(high), MINUTE, MINUTE.plusSeconds(1), false);
    }
}
