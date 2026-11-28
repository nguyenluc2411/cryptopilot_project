package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
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
    void NSF07_untrackingTheLastEntryOfAPair_dropsItsBooks() {
        TrackedEntry entry = entry(MarketType.SPOT, PAIR, Direction.LONG, "100");
        engine.track(entry);

        engine.untrack(entry.planId());

        assertThat(engine.size()).isZero();
        assertThat(engine.onRange(range(MarketType.SPOT, PAIR, "90", "110"))).isEmpty();
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
     * One candle reaching the entry and an invalidation level, whatever its open: the engine invalidates, and the
     * plan's status decides. Its entry is a paper order (RF-33); one that filled first made the plan EXECUTED, and the
     * invalidation's compare-and-set then changes nothing (BR-68, D-117).
     */
    @ParameterizedTest(name = "{0} {1}, open {2}")
    @CsvSource({
        "LIMIT, LONG, 111",
        "LIMIT, LONG, 99",
        "LIMIT, LONG, 105",
        "LIMIT, SHORT, 101",
        "LIMIT, SHORT, 95",
        "STOP, LONG, 101",
        "STOP, LONG, 95",
        "STOP, SHORT, 99",
        "STOP, SHORT, 105"
    })
    void BR68_RF33_aCandleReachingTheEntryAndAnInvalidationLevel_invalidates(
            EntryType entryType, Direction direction, String open) {
        // Every plan has its entry at 100 and its invalidation level at 110 or 90, on the side opposite the entry.
        boolean entryBelow = (entryType == EntryType.LIMIT) == (direction == Direction.LONG);
        String level = entryBelow ? "110" : "90";
        String other = entryBelow ? "90" : "110";
        TrackedEntry plan = entryType == EntryType.LIMIT
                ? plan(entryType, direction, "100", other, level, null)
                : plan(entryType, direction, "100", level, other, null);
        engine.track(plan);

        assertThat(engine.onRange(range(open, "89", "111"))).singleElement().isInstanceOf(Invalidation.class);
        assertThat(engine.onRange(range(null, "89", "111"))).isEmpty();
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
}
