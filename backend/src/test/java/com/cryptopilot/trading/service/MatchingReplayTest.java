package com.cryptopilot.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.FixedClockConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.job.MatchingWorker;
import com.cryptopilot.trading.job.MinuteKlineFeed;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-043 acceptance: a replay equals live processing. One recorded sequence of {@code kline_1m} updates (four updates
 * inside each minute, then the closed candle) runs against the real schema, once fully live and once per way of
 * losing part of it:
 *
 * <ul>
 *   <li>CRASH: the engine stops in the middle of minute 5 and restarts; the rest of the recording arrives live while
 *       the missed minutes are replayed from closed candles, and late duplicates of replayed minutes arrive after;
 *   <li>DROP: the stream loses minutes 3 and 4 while running; the feed replays them;
 *   <li>MISSING_CLOSE: the closed update of minute 3 never arrives; the feed replays the minute;
 *   <li>DROP_THEN_CRASH: the stream loses minutes 3 and 4, the exchange refuses the replay, and the engine crashes in
 *       minute 6; the restart replays the hole;
 *   <li>GIVE_UP_THEN_CRASH: the fill of one plan fails until it is given up, and the engine crashes later; the restart
 *       fills it at the candle that reached it.
 * </ul>
 *
 * Two plans are activated during the run, one in the middle of a minute whose low already reached its entry (D-77).
 * Every run must leave every plan with the same status, fill price and {@code executed_at}, fill no plan from a candle
 * that opened before its activation, apply each closed minute once (one watermark advance per minute) and call the
 * fill of each plan once. No network: the exchange is a stub of {@link MarketApi}. One partition, so the order of the
 * engine's work is the order of the recording.
 *
 * <p>Rule: NSF-07, BR-33; A-04; D-77, D-78, D-79; ADR-011.
 */
@SpringBootTest(
        properties = {
            "cryptopilot.trading.matching.partitions=1",
            "cryptopilot.trading.matching.retry.deadline=0s",
            "cryptopilot.trading.matching.replay.enabled=true",
            "cryptopilot.trading.matching.replay.catch-up-attempts=0",
            "cryptopilot.trading.matching.replay.catch-up-wait=0s"
        })
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class MatchingReplayTest {

    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    /** The first minute of the recording: the minute the fixed clock stands in, so a replay is caught up from here. */
    private static final Instant M0 = FixedClockConfig.NOW.truncatedTo(ChronoUnit.MINUTES);

    private static final Instant BEFORE = M0.minus(Duration.ofHours(1));

    /** Four prices inside each minute, at these seconds; the candle's low and high are cumulative. */
    private static final long[] SECONDS = {5, 20, 40, 55};

    private static final String[][] PATHS = {
        {"100", "99.5", "100.5", "100"},
        {"100", "98", "101", "100"},
        {"100", "102", "101", "101"},
        {"100", "98.8", "100.5", "101"},
        {"101", "103.5", "102", "102"},
        {"102", "101.5", "99", "101"},
        {"101", "105.2", "104", "104"},
        {"104", "103", "104", "104"},
        {"103", "97", "98", "98"},
        {"98", "99", "98.5", "98.5"},
        {"98.5", "98.6", "98.7", "98.6"},
        {"98.6", "98.7", "98.8", "98.7"}
    };

    private static final int LAST = PATHS.length - 1;

    private static final Map<String, Outcome> EXPECTED = Map.of(
            "long98", Outcome.filled("98", minute(1)),
            "short105", Outcome.filled("105", minute(6)),
            "long99MidMinute3", Outcome.filled("99", minute(5)),
            "short103AtMinute4", Outcome.filled("103", minute(4)),
            "long97", Outcome.filled("97", minute(8)),
            "long90", Outcome.active());

    /** The ways of losing part of the recording. */
    enum Loss {
        CRASH,
        DROP,
        MISSING_CLOSE,
        DROP_THEN_CRASH,
        GIVE_UP_THEN_CRASH
    }

    @MockitoBean
    private MarketApi market;

    @MockitoSpyBean
    private MatchingService matching;

    @Autowired
    private MatchingWorker worker;

    @Autowired
    private MinuteKlineFeed feed;

    @Autowired
    private TradingPlanService tradingPlans;

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    /** The closed candles the exchange stub serves, three a page. */
    private final List<MinuteKline> served = new CopyOnWriteArrayList<>();

    private final Map<UUID, Fill> fills = new ConcurrentHashMap<>();
    private final List<Instant> pagesAskedFrom = new CopyOnWriteArrayList<>();

    /** While set, the exchange refuses every page. */
    private volatile boolean refusing;

    /** While set, the fill of this plan fails. */
    private volatile UUID failing;

    /** Called once by the exchange stub, while a replay fetches its first page. */
    private volatile Runnable duringReplay;

    private UUID pair;
    private UUID baseCoin;
    private UUID quoteCoin;

    @BeforeEach
    void setUp() {
        feed.stop();
        worker.stop();
        insertPair();
        when(market.closedMinuteKlines(eq(MarketType.FUTURES), any(), any())).thenAnswer(call -> {
            Instant from = call.getArgument(2);
            pagesAskedFrom.add(from);
            Runnable hook = duringReplay;
            if (hook != null) {
                duringReplay = null;
                hook.run();
            }
            if (refusing) {
                return MinuteKlineBatch.refusedUntil(FixedClockConfig.NOW.plus(Duration.ofHours(1)));
            }
            return MinuteKlineBatch.of(served.stream()
                    .filter(kline -> !kline.openTime().isBefore(from))
                    .limit(3)
                    .toList());
        });
        doAnswer(call -> {
                    Fill fill = call.getArgument(0);
                    if (fill.planId().equals(failing)) {
                        throw new IllegalStateException("database down");
                    }
                    boolean filled = (boolean) call.callRealMethod();
                    if (filled) {
                        fills.put(fill.planId(), fill);
                    }
                    return filled;
                })
                .when(matching)
                .fill(any());
    }

    @AfterEach
    void tearDown() {
        feed.stop();
        worker.stop();
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from matching_watermark where pair_id = ?").param(pair).update();
        jdbc.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        jdbc.sql("delete from coin where coin_id in (?, ?)")
                .params(baseCoin, quoteCoin)
                .update();
    }

    @Test
    void T043_aCrashAndRestartReplay_fillsEveryPlanAsLiveProcessingDoes() {
        Run live = run(null);
        reset();
        Run replayed = run(Loss.CRASH);

        assertThat(replayed.outcomes()).isEqualTo(live.outcomes()).isEqualTo(EXPECTED);
        assertThat(pagesAskedFrom)
                .as("the restart replays from the minute after the watermark, three candles a page")
                .containsSubsequence(minute(5), minute(8), minute(10));
        assertEachMinuteAppliedOnce(live);
        assertEachMinuteAppliedOnce(replayed);
        assertOneFillCallPerPlan(live, null);
        assertOneFillCallPerPlan(replayed, null);
    }

    @ParameterizedTest
    @EnumSource(
            value = Loss.class,
            names = {"DROP", "MISSING_CLOSE", "DROP_THEN_CRASH", "GIVE_UP_THEN_CRASH"})
    void R1_R2_minutesLostWhileRunning_areReplayed_andEveryPlanEndsAsLive(Loss loss) {
        Run run = run(loss);

        assertThat(run.outcomes()).isEqualTo(EXPECTED);
        assertEachMinuteAppliedOnce(run);
        assertOneFillCallPerPlan(
                run, loss == Loss.GIVE_UP_THEN_CRASH ? run.ids().get("long98") : null);
    }

    /** D-79: a Trader's cancel while the pair replays wins over a fill the replay would make from an older candle. */
    @Test
    void D79_aCancelDuringTheReplay_winsOverTheReplayedCandleThatWouldFillThePlan() {
        UUID cancelled = activePlan(Direction.LONG, "98", BEFORE);
        UUID kept = activePlan(Direction.SHORT, "105", BEFORE);
        served.addAll(closedCandles(LAST));
        duringReplay = () -> tradingPlans.cancel(SEEDED_ACCOUNT, cancelled);

        feed.start();
        awaitWatermark(minute(LAST));

        TradingPlan plan = plan(cancelled);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.CANCELLED);
        assertThat(plan.getExecutedAt()).isNull();
        assertThat(plan.getFillPrice()).isNull();
        assertThat(fills).doesNotContainKey(cancelled);
        assertThat(plan(kept).getExecutedAt()).isEqualTo(minute(6));
    }

    @Test
    void A04_theWatermark_isTheLastClosedCandleFullyMatched_andNeverMovesBack() {
        UUID unrelated = UUID.randomUUID();

        matching.advanceWatermark(MarketType.FUTURES, pair, minute(4));
        matching.advanceWatermark(MarketType.FUTURES, pair, minute(2));
        matching.advanceWatermark(MarketType.SPOT, pair, minute(7));

        assertThat(matching.watermark(MarketType.FUTURES, pair)).contains(minute(4));
        assertThat(matching.watermark(MarketType.SPOT, pair)).contains(minute(7));
        assertThat(matching.watermark(MarketType.FUTURES, unrelated)).isEmpty();
    }

    /** One run of the recording, losing part of it as {@code loss} says, or none when {@code null}. */
    private Run run(Loss loss) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        ids.put("long98", activePlan(Direction.LONG, "98", BEFORE));
        ids.put("short105", activePlan(Direction.SHORT, "105", BEFORE));
        ids.put("long97", activePlan(Direction.LONG, "97", BEFORE));
        ids.put("long90", activePlan(Direction.LONG, "90", BEFORE));
        if (loss == Loss.GIVE_UP_THEN_CRASH) {
            failing = ids.get("long98");
        }
        feed.start();
        awaitIdle();

        List<Runnable> steps = new ArrayList<>();
        int crashAt = -1;
        Instant lastClosedBeforeCrash = null;
        for (int m = 0; m <= LAST; m++) {
            int minute = m;
            boolean dropped = (loss == Loss.DROP || loss == Loss.DROP_THEN_CRASH) && (m == 3 || m == 4);
            if (m == 4) {
                steps.add(() -> ids.put("short103AtMinute4", activate(Direction.SHORT, "103", minute(minute))));
            }
            if (m == 5 && loss == Loss.DROP) {
                steps.add(() -> served.addAll(List.of(closedCandle(3), closedCandle(4))));
            }
            if (m == 4 && loss == Loss.MISSING_CLOSE) {
                steps.add(() -> served.add(closedCandle(3)));
            }
            if (m == 5 && loss == Loss.DROP_THEN_CRASH) {
                steps.add(() -> refusing = true);
            }
            for (int i = 0; i < SECONDS.length; i++) {
                int update = i;
                if (m == 3 && i == 2) {
                    steps.add(() -> ids.put(
                            "long99MidMinute3",
                            activate(Direction.LONG, "99", minute(minute).plusSeconds(30))));
                }
                if (crashAt < 0 && crashes(loss, m, i)) {
                    crashAt = steps.size();
                    lastClosedBeforeCrash = minute(loss == Loss.DROP_THEN_CRASH ? 2 : m - 1);
                }
                if (!dropped) {
                    steps.add(() -> feed.onMinuteKline(update(minute, update)));
                }
            }
            if (!dropped && !(loss == Loss.MISSING_CLOSE && m == 3)) {
                steps.add(() -> feed.onMinuteKline(closedCandle(minute)));
            }
        }

        if (crashAt < 0) {
            steps.forEach(Runnable::run);
        } else {
            steps.subList(0, crashAt).forEach(Runnable::run);
            if (loss == Loss.GIVE_UP_THEN_CRASH) {
                awaitFill(ids.get("short105"));
            } else {
                awaitWatermark(lastClosedBeforeCrash);
            }
            feed.stop();
            worker.stop();
            refusing = false;
            failing = null;
            served.clear();
            served.addAll(closedCandles(LAST - 2));
            List<Runnable> rest = List.copyOf(steps.subList(crashAt, steps.size() - (SECONDS.length + 1)));
            duringReplay = () -> rest.forEach(Runnable::run);
            feed.start();
            awaitIdle();
            // Late duplicates of replayed minutes, then the last minute.
            feed.onMinuteKline(closedCandle(LAST - 3));
            feed.onMinuteKline(closedCandle(LAST - 2));
            steps.subList(steps.size() - (SECONDS.length + 1), steps.size()).forEach(Runnable::run);
        }
        awaitWatermark(minute(LAST));
        return new Run(ids, outcomes(ids), List.copyOf(mockingDetails(matching).getInvocations()));
    }

    /** Where the engine crashes: inside minute 5, inside minute 6, or at the start of minute 7. */
    private static boolean crashes(Loss loss, int minute, int update) {
        if (loss == null) {
            return false;
        }
        return switch (loss) {
            case CRASH -> minute == 5 && update == 2;
            case DROP_THEN_CRASH -> minute == 6 && update == 2;
            case GIVE_UP_THEN_CRASH -> minute == 7 && update == 0;
            case DROP, MISSING_CLOSE -> false;
        };
    }

    private void reset() {
        feed.stop();
        worker.stop();
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from matching_watermark where pair_id = ?").param(pair).update();
        served.clear();
        fills.clear();
        pagesAskedFrom.clear();
        clearInvocations(matching);
    }

    /** R1, R5: every closed minute reaches the engine once: one watermark advance per minute, none skipped. */
    private void assertEachMinuteAppliedOnce(Run run) {
        Map<Instant, Integer> advances = new HashMap<>();
        run.invocations().stream()
                .filter(call -> call.getMethod().getName().equals("advanceWatermark"))
                .filter(call -> pair.equals(call.getArgument(1)))
                .forEach(call -> advances.merge(call.getArgument(2), 1, Integer::sum));
        assertThat(advances).as("watermark advances per minute").allSatisfy((minute, count) -> assertThat(count)
                .as("advances to %s", minute)
                .isEqualTo(1));
        List<Instant> everyMinute = new ArrayList<>();
        for (int m = 0; m <= LAST; m++) {
            everyMinute.add(minute(m));
        }
        assertThat(advances.keySet())
                .as("every minute is fully matched, none skipped")
                .containsExactlyInAnyOrderElementsOf(everyMinute);
    }

    /** R5: each filled plan's fill is called once; the plan whose fill failed once more. */
    private void assertOneFillCallPerPlan(Run run, UUID failedOnce) {
        Map<UUID, Integer> calls = new HashMap<>();
        run.invocations().stream()
                .filter(call -> call.getMethod().getName().equals("fill"))
                .forEach(call -> calls.merge(((Fill) call.getArgument(0)).planId(), 1, Integer::sum));
        run.ids().forEach((label, id) -> {
            int expected = EXPECTED.get(label).status() == PlanStatus.EXECUTED ? 1 : 0;
            if (id.equals(failedOnce)) {
                expected = 2;
            }
            assertThat(calls.getOrDefault(id, 0)).as("fill calls of %s", label).isEqualTo(expected);
        });
    }

    private Map<String, Outcome> outcomes(Map<String, UUID> ids) {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        ids.forEach((label, id) -> {
            TradingPlan plan = plan(id);
            if (plan.getExecutedAt() != null) {
                assertThat(plan.getExecutedAt())
                        .as("%s is never filled by a candle that opened before its activation", label)
                        .isAfterOrEqualTo(plan.getActivatedAt());
                assertThat(plan.getFillPrice())
                        .as("%s keeps the fill price the engine decided", label)
                        .isEqualByComparingTo(fills.get(id).price());
            }
            outcomes.put(
                    label,
                    new Outcome(
                            plan.getStatus(),
                            plan.getFillPrice() == null
                                    ? null
                                    : plan.getFillPrice().stripTrailingZeros(),
                            plan.getExecutedAt()));
        });
        return outcomes;
    }

    /** A plan ACTIVE in the database before the engine starts. */
    private UUID activePlan(Direction direction, String entry, Instant activatedAt) {
        return inTransaction(() -> plans.save(plan(direction, entry, activatedAt)))
                .getId();
    }

    /** A plan activated while the engine runs: saved, and its activation published on commit. */
    private UUID activate(Direction direction, String entry, Instant activatedAt) {
        return inTransaction(() -> {
                    TradingPlan plan = plans.save(plan(direction, entry, activatedAt));
                    events.publishEvent(new TradingPlanActivated(
                            plan.getId(),
                            plan.getMarket(),
                            pair,
                            plan.getDirection(),
                            plan.getEntryType(),
                            plan.getEntryPrice(),
                            plan.getActivatedAt()));
                    return plan;
                })
                .getId();
    }

    private TradingPlan plan(Direction direction, String entry, Instant activatedAt) {
        BigDecimal price = new BigDecimal(entry);
        PlanFixture fixture = direction == Direction.LONG
                ? PlanFixture.futuresLong()
                        .entry(entry)
                        .stop(price.subtract(BigDecimal.valueOf(5)).toPlainString())
                        .takeProfit(price.add(BigDecimal.TEN).toPlainString())
                : PlanFixture.futuresShort()
                        .entry(entry)
                        .stop(price.add(BigDecimal.valueOf(5)).toPlainString())
                        .takeProfit(price.subtract(BigDecimal.TEN).toPlainString());
        TradingPlan plan = TradingPlan.draft(
                SEEDED_ACCOUNT,
                pair,
                fixture.build(),
                new PlanDetails(EntryType.LIMIT, activatedAt.plus(Duration.ofDays(7)), null),
                List.of());
        plan.activate(fixture.build(), List.of(), activatedAt);
        return plan;
    }

    private TradingPlan plan(UUID id) {
        return inTransaction(() -> plans.findById(id).orElseThrow());
    }

    private void awaitWatermark(Instant openTime) {
        await(
                () -> Optional.of(openTime).equals(matching.watermark(MarketType.FUTURES, pair)),
                "watermark " + openTime);
    }

    private void awaitFill(UUID planId) {
        await(() -> fills.containsKey(planId), "fill of " + planId);
    }

    private void awaitIdle() {
        await(feed::isIdle, "replays done");
    }

    private static void await(Supplier<Boolean> condition, String what) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!condition.get()) {
            assertThat(Instant.now()).as("%s in time", what).isBefore(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    /** The {@code i}-th update inside minute {@code m}: the low and high of the minute so far. */
    private MinuteKline update(int m, int i) {
        BigDecimal low = new BigDecimal(PATHS[m][0]);
        BigDecimal high = low;
        for (int k = 0; k <= i; k++) {
            BigDecimal price = new BigDecimal(PATHS[m][k]);
            low = low.min(price);
            high = high.max(price);
        }
        return new MinuteKline(
                MarketType.FUTURES, pair, minute(m), low, high, false, minute(m).plusSeconds(SECONDS[i]));
    }

    private MinuteKline closedCandle(int m) {
        MinuteKline last = update(m, SECONDS.length - 1);
        return new MinuteKline(
                MarketType.FUTURES,
                pair,
                minute(m),
                last.low(),
                last.high(),
                true,
                minute(m).plusSeconds(60).minusMillis(1));
    }

    /** The closed candles of minutes 0 to {@code last}. */
    private List<MinuteKline> closedCandles(int last) {
        List<MinuteKline> candles = new ArrayList<>();
        for (int m = 0; m <= last; m++) {
            candles.add(closedCandle(m));
        }
        return candles;
    }

    private static Instant minute(long n) {
        return M0.plus(Duration.ofMinutes(n));
    }

    private void insertPair() {
        OffsetDateTime at = FixedClockConfig.NOW.atOffset(ZoneOffset.UTC);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        baseCoin = UUID.randomUUID();
        quoteCoin = UUID.randomUUID();
        pair = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{baseCoin, "RPB" + suffix}, {quoteCoin, "RPQ" + suffix}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', 'TRADING', ?, ?)""").params(pair, baseCoin, quoteCoin, "RP" + suffix, at, at).update();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    /** A run: the plans by label, what it left them with, and every call on the matching service. */
    private record Run(Map<String, UUID> ids, Map<String, Outcome> outcomes, List<Invocation> invocations) {}

    /** What a run left a plan with. */
    private record Outcome(PlanStatus status, BigDecimal fillPrice, Instant executedAt) {

        static Outcome filled(String price, Instant at) {
            return new Outcome(PlanStatus.EXECUTED, new BigDecimal(price).stripTrailingZeros(), at);
        }

        static Outcome active() {
            return new Outcome(PlanStatus.ACTIVE, null, null);
        }
    }
}
