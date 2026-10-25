package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * The books of one partition: LIMIT and STOP entries on their sides, invalidation levels on the opposite side, and the
 * same-candle rule.
 *
 * <p>Rule: NSF-07, BR-33, BR-68; TECHNICAL_DESIGN 7.7; D-77, D-78; D-111 (M1, M3, E6, E7); D-112 (R3, R4).
 */
class MatchingEngineTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final UUID OTHER_PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b002");
    /** The open time of the candle the test ranges belong to. */
    private static final Instant MINUTE = Instant.parse("2026-10-03T08:00:00Z");

    private static final Instant ACTIVATED = MINUTE.minusSeconds(3600);

    private final MatchingEngine engine = new MatchingEngine();

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger ruleLogger = (Logger) LoggerFactory.getLogger(WaitingPlanRule.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        ruleLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ruleLogger.detachAppender(logs);
    }

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
                .satisfies(fill -> assertThat(((Fill) fill).price()).isEqualByComparingTo("100"));
        assertThat(engine.onRange(range(MarketType.SPOT, OTHER_PAIR, "105", "110")))
                .singleElement()
                .satisfies(fill -> assertThat(((Fill) fill).price()).isEqualByComparingTo("100"));
    }

    @Test
    void D78_aFill_isRecordedAtTheOpenTimeOfItsCandle_notAtTheUpdateTime() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        engine.track(entry);

        PriceRange update = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("101"), MINUTE, MINUTE.plusSeconds(37));

        assertThat(engine.onRange(update))
                .containsExactly(new Fill(entry, LiquidityRole.MAKER, new BigDecimal("100"), MINUTE));
    }

    @Test
    void D77_aCandleThatOpenedBeforeTheActivation_doesNotFill_andTheEntryWaitsForTheNextCandle() {
        Instant activated = MINUTE.plusSeconds(30);
        TrackedEntry entry = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100"), activated);
        engine.track(entry);

        PriceRange sameMinute = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("101"), MINUTE, MINUTE.plusSeconds(45));
        PriceRange nextMinute = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("99"), new BigDecimal("101"), MINUTE.plusSeconds(60));

        assertThat(engine.onRange(sameMinute)).isEmpty();
        assertThat(engine.size()).isEqualTo(1);
        assertThat(engine.onRange(nextMinute))
                .containsExactly(new Fill(entry, LiquidityRole.MAKER, new BigDecimal("100"), MINUTE.plusSeconds(60)));
    }

    @Test
    void D77_aCandleThatOpenedAtTheActivationInstant_fills() {
        TrackedEntry entry = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.SPOT, PAIR, Direction.SHORT, new BigDecimal("100"), MINUTE);
        engine.track(entry);

        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "99", "101"))).containsExactly(fill(entry));
    }

    @Test
    void D77_anEntryHeldBackByItsActivation_doesNotHoldBackOlderEntriesOfTheSameLevel() {
        TrackedEntry older = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        TrackedEntry newer = TrackedEntries.limit(
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
        engine.track(
                TrackedEntries.limit(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100"), ACTIVATED));
        TrackedEntry moved =
                TrackedEntries.limit(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("90"), ACTIVATED);

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

    @Test
    void BR33_aStopLong_waitsAbove_andFillsAsTakerAtItsStopWhenTheHighReachesIt() {
        TrackedEntry stop = plan(EntryType.STOP, Direction.LONG, "100", "95", "110", null);
        engine.track(stop);

        assertThat(engine.onRange(range("99", "98", "99.99"))).isEmpty();
        assertThat(engine.onRange(range("99.5", "99", "100")))
                .containsExactly(new Fill(stop, LiquidityRole.TAKER, new BigDecimal("100"), MINUTE));
        assertThat(engine.size()).isZero();
    }

    @Test
    void BR33_aStopShort_waitsBelow_andFillsAsTakerAtItsStopWhenTheLowReachesIt() {
        TrackedEntry stop = plan(EntryType.STOP, Direction.SHORT, "100", "105", "90", null);
        engine.track(stop);

        assertThat(engine.onRange(range("101", "100.01", "102"))).isEmpty();
        assertThat(engine.onRange(range("100.5", "100", "101")))
                .containsExactly(new Fill(stop, LiquidityRole.TAKER, new BigDecimal("100"), MINUTE));
    }

    @Test
    void BR33_aStopTheCandleOpenedPast_fillsAtTheOpen() {
        TrackedEntry longStop = plan(EntryType.STOP, Direction.LONG, "100", "95", "110", null);
        TrackedEntry shortStop = plan(EntryType.STOP, Direction.SHORT, "100", "105", "90", null, OTHER_PAIR);
        engine.track(longStop);
        engine.track(shortStop);

        assertThat(engine.onRange(range("102", "101", "103")))
                .containsExactly(new Fill(longStop, LiquidityRole.TAKER, new BigDecimal("102"), MINUTE));
        assertThat(engine.onRange(range(OTHER_PAIR, "97", "96", "98")))
                .containsExactly(new Fill(shortStop, LiquidityRole.TAKER, new BigDecimal("97"), MINUTE));
    }

    @ParameterizedTest(name = "LIMIT {0} entry {1}, TP {2}: range {3}..{4}")
    @CsvSource({"LONG, 100, 110, 101, 110", "SHORT, 100, 90, 90, 99"})
    void BR68_aLimitWhoseTakeProfitIsReachedFirst_isInvalidated_andLeavesBothBooks(
            Direction direction, String entry, String takeProfit, String low, String high) {
        TrackedEntry limit =
                plan(EntryType.LIMIT, direction, entry, direction == Direction.LONG ? "95" : "105", takeProfit, null);
        engine.track(limit);

        assertThat(engine.onRange(range(null, low, high)))
                .containsExactly(new Invalidation(limit, InvalidationReason.TAKE_PROFIT_REACHED, MINUTE));
        assertThat(engine.size()).isZero();
        assertThat(engine.onRange(range(null, "1", "1000"))).isEmpty();
    }

    @ParameterizedTest(name = "STOP {0} stop {1}, SL {2}: range {3}..{4}")
    @CsvSource({"LONG, 100, 95, 95, 99", "SHORT, 100, 105, 101, 105"})
    void BR68_aStopWhoseStopLossIsReachedFirst_isInvalidated(
            Direction direction, String stop, String stopLoss, String low, String high) {
        TrackedEntry plan =
                plan(EntryType.STOP, direction, stop, stopLoss, direction == Direction.LONG ? "110" : "90", null);
        engine.track(plan);

        assertThat(engine.onRange(range(null, low, high)))
                .containsExactly(new Invalidation(plan, InvalidationReason.STOP_LOSS_REACHED, MINUTE));
        assertThat(engine.size()).isZero();
    }

    @Test
    void BR68_theInvalidationPriceReachedFirst_invalidatesALimitAndAStop() {
        TrackedEntry limit = plan(EntryType.LIMIT, Direction.LONG, "100", "95", "110", "105");
        TrackedEntry stop = plan(EntryType.STOP, Direction.SHORT, "100", "110", "90", "104", OTHER_PAIR);
        engine.track(limit);
        engine.track(stop);

        assertThat(engine.onRange(range(null, "102", "105")))
                .containsExactly(new Invalidation(limit, InvalidationReason.INVALIDATION_PRICE, MINUTE));
        assertThat(engine.onRange(range(OTHER_PAIR, null, "101", "104")))
                .containsExactly(new Invalidation(stop, InvalidationReason.INVALIDATION_PRICE, MINUTE));
    }

    /** Two invalidation levels in one candle: the price passed the nearer one first. */
    @Test
    void BR68_aCandleReachingBothInvalidationLevels_namesTheNearerOne() {
        TrackedEntry invalidationFirst = plan(EntryType.LIMIT, Direction.LONG, "100", "95", "110", "105");
        TrackedEntry stopLossFirst = plan(EntryType.STOP, Direction.LONG, "100", "97", "110", "96", OTHER_PAIR);
        engine.track(invalidationFirst);
        engine.track(stopLossFirst);

        assertThat(engine.onRange(range(null, "101", "111")))
                .containsExactly(new Invalidation(invalidationFirst, InvalidationReason.INVALIDATION_PRICE, MINUTE));
        assertThat(engine.onRange(range(OTHER_PAIR, null, "90", "99")))
                .containsExactly(new Invalidation(stopLossFirst, InvalidationReason.STOP_LOSS_REACHED, MINUTE));
    }

    /**
     * One candle reaching the entry and an invalidation level: the open decides when it lies at or past one of them;
     * opened between them, invalidation wins (D-111 E6).
     */
    @ParameterizedTest(name = "{0} {1}, open {2}: {3}")
    @CsvSource({
        "LIMIT, LONG, 111, INVALIDATED",
        "LIMIT, LONG, 110, INVALIDATED",
        "LIMIT, LONG, 99, FILLED",
        "LIMIT, LONG, 100, FILLED",
        "LIMIT, LONG, 105, INVALIDATED",
        "LIMIT, SHORT, 89, INVALIDATED",
        "LIMIT, SHORT, 101, FILLED",
        "LIMIT, SHORT, 95, INVALIDATED",
        "STOP, LONG, 89, INVALIDATED",
        "STOP, LONG, 101, FILLED",
        "STOP, LONG, 95, INVALIDATED",
        "STOP, SHORT, 111, INVALIDATED",
        "STOP, SHORT, 99, FILLED",
        "STOP, SHORT, 105, INVALIDATED"
    })
    void BR68_E6_aCandleReachingTheEntryAndAnInvalidationLevel_isDecidedByItsOpen_elseInvalidationWins(
            EntryType entryType, Direction direction, String open, String outcome) {
        // Every plan has its entry at 100 and its invalidation level at 110 or 90, on the side opposite the entry.
        boolean entryBelow = (entryType == EntryType.LIMIT) == (direction == Direction.LONG);
        String level = entryBelow ? "110" : "90";
        String other = entryBelow ? "90" : "110";
        TrackedEntry plan = entryType == EntryType.LIMIT
                ? plan(entryType, direction, "100", other, level, null)
                : plan(entryType, direction, "100", level, other, null);
        engine.track(plan);

        List<PlanOutcome> outcomes = engine.onRange(range(open, "89", "111"));

        assertThat(outcomes).singleElement().satisfies(decided -> {
            if (outcome.equals("FILLED")) {
                Fill fill = (Fill) decided;
                // A LIMIT keeps its own price (E7); a STOP the candle opened past fills at the open.
                assertThat(fill.price())
                        .isEqualByComparingTo(
                                entryType == EntryType.LIMIT ? new BigDecimal("100") : new BigDecimal(open));
            } else {
                assertThat(decided).isInstanceOf(Invalidation.class);
            }
        });
        assertThat(logged()).isEmpty();
    }

    /** D-112 R4: the same candle with an unknown open counts as invalidation first, and the case is logged. */
    @Test
    void D112R4_aCandleReachingTheEntryAndAnInvalidationLevelWithAnUnknownOpen_invalidates_andIsLogged() {
        TrackedEntry limit = plan(EntryType.LIMIT, Direction.LONG, "100", "95", "110", null);
        engine.track(limit);

        assertThat(engine.onRange(range(null, "99", "111")))
                .containsExactly(new Invalidation(limit, InvalidationReason.TAKE_PROFIT_REACHED, MINUTE));
        assertThat(logged()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains(limit.planId().toString(), PAIR.toString(), MINUTE.toString(), "unknown open"));
    }

    /** D-77 (D-112 R3): a candle that opened before the activation does not invalidate either. */
    @Test
    void D77_aCandleThatOpenedBeforeTheActivation_doesNotInvalidate_andTheLevelsWaitForTheNextCandle() {
        Instant activated = MINUTE.plusSeconds(30);
        TrackedEntry limit = TrackedEntries.of(
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                Direction.LONG,
                EntryType.LIMIT,
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                null,
                activated);
        engine.track(limit);

        PriceRange sameMinute = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("105"), new BigDecimal("111"), MINUTE, MINUTE.plusSeconds(45));
        PriceRange nextMinute = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("105"), new BigDecimal("111"), MINUTE.plusSeconds(60));

        assertThat(engine.onRange(sameMinute)).isEmpty();
        assertThat(engine.size()).isEqualTo(1);
        assertThat(engine.onRange(nextMinute))
                .containsExactly(
                        new Invalidation(limit, InvalidationReason.TAKE_PROFIT_REACHED, MINUTE.plusSeconds(60)));
    }

    @Test
    void BR68_untrackingAPlan_removesItsInvalidationLevelsToo() {
        TrackedEntry limit = plan(EntryType.LIMIT, Direction.LONG, "100", "95", "110", "105");
        engine.track(limit);

        engine.untrack(limit.planId());

        assertThat(engine.onRange(range(null, "104", "120"))).isEmpty();
        assertThat(engine.size()).isZero();
    }

    @Test
    void BR68_trackingAPlanAgain_replacesItsInvalidationLevels() {
        UUID planId = UUID.randomUUID();
        engine.track(plan(planId, EntryType.LIMIT, Direction.LONG, "100", "95", "110", "105"));
        TrackedEntry moved = plan(planId, EntryType.LIMIT, Direction.LONG, "100", "95", "120", null);

        engine.track(moved);

        assertThat(engine.onRange(range(null, "101", "119"))).isEmpty();
        assertThat(engine.onRange(range(null, "101", "120")))
                .containsExactly(new Invalidation(moved, InvalidationReason.TAKE_PROFIT_REACHED, MINUTE));
    }

    @Test
    void BR68_aPlanWithTwoLevelsAtOnePrice_isDecidedOnce_andNamesItsTakeProfit() {
        TrackedEntry limit = plan(EntryType.LIMIT, Direction.LONG, "100", "95", "110", "110");
        engine.track(limit);

        assertThat(engine.onRange(range(null, "101", "112")))
                .containsExactly(new Invalidation(limit, InvalidationReason.TAKE_PROFIT_REACHED, MINUTE));
        assertThat(engine.size()).isZero();
    }

    private static TrackedEntry plan(
            EntryType entryType,
            Direction direction,
            String entry,
            String stopLoss,
            String takeProfit,
            String invalidation) {
        return plan(entryType, direction, entry, stopLoss, takeProfit, invalidation, PAIR);
    }

    private static TrackedEntry plan(
            EntryType entryType,
            Direction direction,
            String entry,
            String stopLoss,
            String takeProfit,
            String invalidation,
            UUID pair) {
        return TrackedEntries.of(
                UUID.randomUUID(),
                MarketType.SPOT,
                pair,
                direction,
                entryType,
                new BigDecimal(entry),
                new BigDecimal(stopLoss),
                new BigDecimal(takeProfit),
                invalidation == null ? null : new BigDecimal(invalidation),
                ACTIVATED);
    }

    private static TrackedEntry plan(
            UUID planId,
            EntryType entryType,
            Direction direction,
            String entry,
            String stopLoss,
            String takeProfit,
            String invalidation) {
        return TrackedEntries.of(
                planId,
                MarketType.SPOT,
                PAIR,
                direction,
                entryType,
                new BigDecimal(entry),
                new BigDecimal(stopLoss),
                new BigDecimal(takeProfit),
                invalidation == null ? null : new BigDecimal(invalidation),
                ACTIVATED);
    }

    /** A range of {@link #PAIR} in {@link #MINUTE} with this open ({@code null}: unknown), low and high. */
    private static PriceRange range(String open, String low, String high) {
        return range(PAIR, open, low, high);
    }

    private static PriceRange range(UUID pair, String open, String low, String high) {
        return new PriceRange(
                MarketType.SPOT,
                pair,
                open == null ? null : new BigDecimal(open),
                new BigDecimal(low),
                new BigDecimal(high),
                MINUTE,
                MINUTE.plusSeconds(20),
                false);
    }

    private List<ILoggingEvent> logged() {
        return List.copyOf(logs.list);
    }

    private static TrackedEntry entry(MarketType market, UUID pair, Direction direction, String price) {
        return TrackedEntries.limit(UUID.randomUUID(), market, pair, direction, new BigDecimal(price), ACTIVATED);
    }

    private static PriceRange range(MarketType market, UUID pair, String low, String high) {
        return new PriceRange(market, pair, new BigDecimal(low), new BigDecimal(high), MINUTE);
    }

    /** The fill of an entry reached by a range of {@link #MINUTE}: the entry price at the candle's open time. */
    private static Fill fill(TrackedEntry entry) {
        return new Fill(entry, LiquidityRole.MAKER, entry.entryPrice(), MINUTE);
    }
}
