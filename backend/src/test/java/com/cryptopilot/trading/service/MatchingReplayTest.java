package com.cryptopilot.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarkPrice;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.FixedClockConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.job.MatchingWorker;
import com.cryptopilot.trading.job.MinuteKlineFeed;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.user.model.enums.UserRole;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-043 and TP-B acceptance: a replay equals live processing. One recorded sequence of {@code kline_1m} updates (four
 * updates inside each minute, then the closed candle, each with the candle's open) runs against the real schema, once
 * fully live and once per way of losing part of it:
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
 * Besides the LIMIT entries, the recording triggers a STOP at its stop (minute 6) and a STOP through a gap at the open
 * (minute 8), invalidates a LIMIT by its take profit (minute 6), a STOP by its stop loss and a LIMIT by its
 * invalidation price (minute 8), most of them during the downtime of the crashes. Slippage is 10 bps, so the STOP fills
 * are stored at the slipped price. The STOP plans, activated before the start, are rebuilt into the books at each
 * start, as the ACTIVE STOP plans activated before the engine knew STOP entries are. Three plans are activated during
 * the run, one in the middle of a minute whose low already reached its entry (D-77), one a STOP that a crash's replay
 * receives while it runs. Every run must leave every plan with the same status, fill price, liquidity role and
 * {@code executed_at}, or the same invalidation reason and {@code invalidated_at}, act on no plan from a candle that
 * opened before its activation, apply each closed minute once (one watermark advance per minute) and store each outcome
 * once. The positions the fills open exit the same way: the STOP filled at minute 6 is stopped at minute 8, and a LIMIT
 * at 20x is liquidated by the mark price of minute 7, both during the downtime of the crashes, so the mark price candles
 * are replayed beside the last price ones; every run must leave every journal row with the same exit, and a crash the
 * same amounts as live. {@code SLIPPAGE_BPS} is changed through the admin endpoint, so the setting reader's cache is evicted as it
 * would be in use, and restored after each test. No network: the exchange is a stub of {@link MarketApi}. One partition, so the order of the engine's work is
 * the order of the recording.
 *
 * <p>Rule: NSF-07, BR-33, BR-35, BR-36, BR-68; A-04; D-77, D-78, D-79; D-111 (M1, M3, E5); D-112 (R6); Q-59;
 * ADR-011.
 */
@SpringBootTest(
        properties = {
            "cryptopilot.trading.matching.partitions=1",
            "cryptopilot.trading.matching.retry.deadline=0s",
            "cryptopilot.trading.matching.replay.enabled=true",
            "cryptopilot.trading.matching.replay.catch-up-attempts=0",
            "cryptopilot.trading.matching.replay.catch-up-wait=0s"
        })
@AutoConfigureMockMvc
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
        {"104", "103.5", "104", "104"},
        {"103", "97", "98", "98"},
        {"98", "99", "98.5", "98.5"},
        {"98.5", "98.6", "98.7", "98.6"},
        {"98.6", "98.7", "98.8", "98.7"}
    };

    private static final int LAST = PATHS.length - 1;

    /** The mark price of each minute: steady, but for minute 7, which liquidates the 20x LONG at 96.34. */
    private static final String[] MARKS = {
        "100", "100", "100", "100", "100", "100", "100", "96.2", "100", "100", "100", "100"
    };

    /** The exits: the reason, the stored exit price and the exit time of each position that closes. */
    private static final Map<String, Exited> EXITED = Map.of(
            // Stopped at 97.5 by minute 8's low of 97, sold 10 bps lower: 97.4025 rounded down to the tick.
            "stopLong104.5", new Exited(CloseReason.STOP_LOSS, new BigDecimal("97.4"), minute(8)),
            // 2 at 101, 20x: (202 − 10.1) / (2 × 0.996) = 96.335…, up to the tick; liquidated there, no slippage.
            "long101x20", new Exited(CloseReason.LIQUIDATION, new BigDecimal("96.34"), minute(7)));

    private static final Map<String, Outcome> EXPECTED = Map.ofEntries(
            Map.entry("long98", Outcome.filled("98", minute(1))),
            Map.entry("long101x20", Outcome.filled("101", minute(0))),
            Map.entry("short105", Outcome.filled("105", minute(6))),
            Map.entry("long99MidMinute3", Outcome.filled("99", minute(5))),
            Map.entry("short103AtMinute4", Outcome.filled("103", minute(4))),
            Map.entry("long97", Outcome.filled("97", minute(8))),
            Map.entry("long90", Outcome.active()),
            // Triggered at 104.5 by minute 6's high, bought 10 bps higher: 104.6045 rounded up to the tick.
            Map.entry("stopLong104.5", Outcome.takerFilled("104.61", minute(6))),
            // Minute 8 opened at 103, past the stop: sold at the open, 10 bps lower, 102.897 rounded down.
            Map.entry("stopShort103.2AtMinute7", Outcome.takerFilled("102.89", minute(8))),
            Map.entry("limitLong96", Outcome.invalidated(InvalidationReason.TAKE_PROFIT_REACHED, minute(6))),
            Map.entry("stopLong106", Outcome.invalidated(InvalidationReason.STOP_LOSS_REACHED, minute(8))),
            Map.entry("limitShort106", Outcome.invalidated(InvalidationReason.INVALIDATION_PRICE, minute(8))));

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

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private Clock clock;

    /** The administrator who changes {@code SLIPPAGE_BPS}, and the setting's audit columns before the change. */
    private UUID admin;

    private Map<String, Object> settingAudit;

    /** The closed candles the exchange stub serves, three a page. */
    private final List<MinuteKline> served = new CopyOnWriteArrayList<>();

    /** The closed mark price candles the exchange stub serves, three a page. */
    private final List<MinuteKline> servedMarks = new CopyOnWriteArrayList<>();

    private final Map<UUID, Fill> fills = new ConcurrentHashMap<>();
    private final Map<UUID, Invalidation> invalidations = new ConcurrentHashMap<>();
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
    void setUp() throws Exception {
        feed.stop();
        worker.stop();
        insertPair();
        admin = insertAdministrator();
        settingAudit = jdbc.sql("select updated_by, updated_at from system_setting where setting_key = 'SLIPPAGE_BPS'")
                .query()
                .singleRow();
        changeSlippage("10");
        when(market.tradablePair(any(), eq(MarketType.FUTURES)))
                .thenAnswer(call -> Optional.of(new TradablePair(
                        call.getArgument(0),
                        "RPUSDT",
                        MarketType.FUTURES,
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")),
                        true)));
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
        when(market.closedMarkPriceKlines(any(), any())).thenAnswer(call -> {
            Instant from = call.getArgument(1);
            if (refusing) {
                return MinuteKlineBatch.refusedUntil(FixedClockConfig.NOW.plus(Duration.ofHours(1)));
            }
            return MinuteKlineBatch.of(servedMarks.stream()
                    .filter(kline -> !kline.openTime().isBefore(from))
                    .limit(3)
                    .toList());
        });
        when(market.leverageBrackets(any()))
                .thenReturn(List.of(new LeverageTier(
                        1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO)));
        when(market.fundingHistory(any(), any(), any()))
                .thenReturn(new FundingHistory(List.of(), List.of(), List.of(), List.of(), List.of()));
        doAnswer(call -> {
                    Fill fill = call.getArgument(0);
                    if (fill.planId().equals(failing)) {
                        throw new IllegalStateException("database down");
                    }
                    Optional<?> filled = (Optional<?>) call.callRealMethod();
                    if (filled.isPresent()) {
                        fills.put(fill.planId(), fill);
                    }
                    return filled;
                })
                .when(matching)
                .fill(any());
        doAnswer(call -> {
                    boolean invalidated = (boolean) call.callRealMethod();
                    if (invalidated) {
                        Invalidation invalidation = call.getArgument(0);
                        invalidations.put(invalidation.planId(), invalidation);
                    }
                    return invalidated;
                })
                .when(matching)
                .invalidate(any());
    }

    @AfterEach
    void tearDown() throws Exception {
        feed.stop();
        worker.stop();
        changeSlippage("0");
        jdbc.sql("update system_setting set updated_by = ?, updated_at = ? where setting_key = 'SLIPPAGE_BPS'")
                .params(settingAudit.get("updated_by"), settingAudit.get("updated_at"))
                .update();
        jdbc.sql("delete from audit_log where user_id = ?").param(admin).update();
        jdbc.sql("delete from user_account where user_id = ?").param(admin).update();
        deleteNotifications();
        jdbc.sql("delete from trading_journal where pair_id = ?").param(pair).update();
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
        assertThat(replayed.journal())
                .as("an exit during the downtime gives the same journal row as live processing")
                .isEqualTo(live.journal());
        assertExits(live);
        assertThat(pagesAskedFrom)
                .as("the restart replays from the minute after the watermark, three candles a page")
                .containsSubsequence(minute(5), minute(8), minute(10));
        assertEachMinuteAppliedOnce(live);
        assertEachMinuteAppliedOnce(replayed);
        assertOneStoreCallPerPlan(live, null);
        assertOneStoreCallPerPlan(replayed, null);
    }

    @ParameterizedTest
    @EnumSource(
            value = Loss.class,
            names = {"DROP", "MISSING_CLOSE", "DROP_THEN_CRASH", "GIVE_UP_THEN_CRASH"})
    void R1_R2_minutesLostWhileRunning_areReplayed_andEveryPlanEndsAsLive(Loss loss) {
        Run run = run(loss);

        assertThat(run.outcomes()).isEqualTo(EXPECTED);
        assertExits(run);
        assertEachMinuteAppliedOnce(run);
        assertOneStoreCallPerPlan(
                run, loss == Loss.GIVE_UP_THEN_CRASH ? run.ids().get("long98") : null);
    }

    /**
     * D-79: a Trader's cancel while the pair replays wins over a fill or an invalidation the replay would make from an
     * older candle: a LIMIT it would fill, a STOP it would trigger, a LIMIT whose take profit it would reach.
     */
    @Test
    void D79_aCancelDuringTheReplay_winsOverTheReplayedCandleThatWouldFillOrInvalidateThePlan() {
        UUID cancelled = activePlan(Direction.LONG, "98", BEFORE);
        UUID cancelledStop = activePlan(Direction.LONG, EntryType.STOP, "104.5", "97.5", "115", null, BEFORE);
        UUID cancelledBeforeItsTakeProfit =
                activePlan(Direction.LONG, EntryType.LIMIT, "96", "91", "105", null, BEFORE);
        UUID kept = activePlan(Direction.SHORT, "105", BEFORE);
        UUID keptInvalidated = activePlan(Direction.LONG, EntryType.STOP, "106", "97.5", "116", null, BEFORE);
        served.addAll(closedCandles(LAST));
        duringReplay = () -> List.of(cancelled, cancelledStop, cancelledBeforeItsTakeProfit)
                .forEach(id -> tradingPlans.cancel(SEEDED_ACCOUNT, id));

        feed.start();
        awaitWatermark(minute(LAST));

        for (UUID id : List.of(cancelled, cancelledStop, cancelledBeforeItsTakeProfit)) {
            TradingPlan plan = plan(id);
            assertThat(plan.getStatus()).isEqualTo(PlanStatus.CANCELLED);
            assertThat(plan.getExecutedAt()).isNull();
            assertThat(plan.getFillPrice()).isNull();
            assertThat(plan.getInvalidatedAt()).isNull();
            assertThat(fills).doesNotContainKey(id);
            assertThat(invalidations).doesNotContainKey(id);
        }
        assertThat(plan(kept).getExecutedAt()).isEqualTo(minute(6));
        assertThat(plan(keptInvalidated).getInvalidatedAt()).isEqualTo(minute(8));
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
        ids.put(
                "long101x20",
                inTransaction(() -> plans.save(leveraged(Direction.LONG, "101", 20, BEFORE)))
                        .getId());
        ids.put("short105", activePlan(Direction.SHORT, "105", BEFORE));
        ids.put("long97", activePlan(Direction.LONG, "97", BEFORE));
        ids.put("long90", activePlan(Direction.LONG, EntryType.LIMIT, "90", "85", "120", null, BEFORE));
        ids.put("stopLong104.5", activePlan(Direction.LONG, EntryType.STOP, "104.5", "97.5", "115", null, BEFORE));
        ids.put("limitLong96", activePlan(Direction.LONG, EntryType.LIMIT, "96", "91", "105", null, BEFORE));
        ids.put("stopLong106", activePlan(Direction.LONG, EntryType.STOP, "106", "97.5", "116", null, BEFORE));
        ids.put("limitShort106", activePlan(Direction.SHORT, EntryType.LIMIT, "106", "111", "90", "97.5", BEFORE));
        if (loss == Loss.GIVE_UP_THEN_CRASH) {
            failing = ids.get("long98");
        }
        // Live up to the recording: the stub serves no candle before it, and a replay that cannot reach M0 retries.
        OffsetDateTime beforeRecording = M0.minus(Duration.ofMinutes(1)).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        insert into matching_watermark (market_type, pair_id, last_candle_open_time, updated_at)
                        values ('FUTURES', ?, ?, ?)""").params(pair, beforeRecording, beforeRecording).update();
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
            if (m == 7) {
                steps.add(() -> ids.put(
                        "stopShort103.2AtMinute7",
                        activate(Direction.SHORT, EntryType.STOP, "103.2", "108", "93", null, minute(minute))));
            }
            if (m == 5 && loss == Loss.DROP) {
                steps.add(() -> {
                    served.addAll(List.of(closedCandle(3), closedCandle(4)));
                    servedMarks.addAll(List.of(closedMark(3), closedMark(4)));
                });
            }
            if (m == 4 && loss == Loss.MISSING_CLOSE) {
                steps.add(() -> {
                    served.add(closedCandle(3));
                    servedMarks.add(closedMark(3));
                });
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
            if (!dropped && m < LAST) {
                steps.add(() -> feed.onMarkPrice(markPrice(minute)));
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
                // The given-up fill holds the watermark, so wait for every outcome stored before the crash instead.
                awaitStoredUpTo(ids, lastClosedBeforeCrash, ids.get("long98"));
            } else {
                awaitWatermark(lastClosedBeforeCrash);
            }
            feed.stop();
            worker.stop();
            refusing = false;
            failing = null;
            served.clear();
            served.addAll(closedCandles(LAST - 2));
            servedMarks.clear();
            for (int m = 0; m <= LAST - 2; m++) {
                servedMarks.add(closedMark(m));
            }
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
        awaitExits(ids);
        return new Run(
                ids,
                outcomes(ids),
                journal(ids),
                List.copyOf(mockingDetails(matching).getInvocations()));
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
        deleteNotifications();
        jdbc.sql("delete from trading_journal where pair_id = ?").param(pair).update();
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from matching_watermark where pair_id = ?").param(pair).update();
        served.clear();
        servedMarks.clear();
        fills.clear();
        invalidations.clear();
        pagesAskedFrom.clear();
        clearInvocations(matching);
    }

    /** The positions that close do so at the expected reason, price and time; the others stay OPEN. */
    private void assertExits(Run run) {
        run.journal().forEach((label, row) -> {
            Exited exited = EXITED.get(label);
            if (exited == null) {
                assertThat(row.status()).as("%s stays open", label).isEqualTo("OPEN");
                return;
            }
            assertThat(row.status()).as("%s closes", label).isEqualTo("CLOSED");
            assertThat(row.closeReason())
                    .as("%s close reason", label)
                    .isEqualTo(exited.reason().name());
            assertThat(row.exitPrice()).as("%s exit price", label).isEqualByComparingTo(exited.price());
            assertThat(row.exitTime()).as("%s exit time", label).isEqualTo(exited.at());
        });
    }

    /** Waits until the positions that close are committed CLOSED; an exit is stored after the candle's watermark. */
    private void awaitExits(Map<String, UUID> ids) {
        List<UUID> closing = EXITED.keySet().stream().map(ids::get).toList();
        awaitUntil(
                () -> jdbc.sql(
                                        "select count(*) from trading_journal where plan_id in (:ids) and trade_status = 'CLOSED'")
                                .param("ids", closing)
                                .query(Long.class)
                                .single()
                        == closing.size(),
                "the exits committed");
    }

    /** The journal row of each filled plan: what its position closed with, or OPEN. */
    private Map<String, JournalRow> journal(Map<String, UUID> ids) {
        Map<String, JournalRow> rows = new LinkedHashMap<>();
        ids.forEach((label, id) -> jdbc.sql("""
                                select trade_status, close_reason, exit_price, exit_time, realized_pnl, fee_amount,
                                       funding_fee_amount, funding_incomplete
                                  from trading_journal where plan_id = ?""")
                .param(id)
                .query((row, number) -> new JournalRow(
                        row.getString("trade_status"),
                        row.getString("close_reason"),
                        stripped(row.getBigDecimal("exit_price")),
                        row.getTimestamp("exit_time") == null
                                ? null
                                : row.getTimestamp("exit_time").toInstant(),
                        stripped(row.getBigDecimal("realized_pnl")),
                        stripped(row.getBigDecimal("fee_amount")),
                        stripped(row.getBigDecimal("funding_fee_amount")),
                        row.getBoolean("funding_incomplete")))
                .optional()
                .ifPresent(row -> rows.put(label, row)));
        return rows;
    }

    private static BigDecimal stripped(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    /**
     * Waits until every closed position and every invalidated plan of the pair has its notification (MSG23, MSG24,
     * MSG54), written after the commit, and deletes them, so no row of this test is left for the tests that follow.
     */
    private void deleteNotifications() {
        awaitUntil(
                () -> jdbc.sql("""
                                        select count(*) from trading_journal j
                                         where j.pair_id = ? and j.trade_status = 'CLOSED'
                                           and not exists (select 1 from notification n
                                                            where n.source_key = 'POSITION_CLOSED:' || j.journal_id)""").param(pair).query(Long.class).single() == 0,
                "the notifications of the closed positions");
        awaitUntil(
                () -> jdbc.sql("""
                                        select count(*) from trading_plan p
                                         where p.pair_id = ? and p.plan_status = 'INVALIDATED'
                                           and not exists (select 1 from notification n
                                                            where n.source_key = 'PLAN_INVALIDATED:' || p.plan_id)""").param(pair).query(Long.class).single() == 0,
                "the notifications of the invalidated plans");
        jdbc.sql("""
                        delete from notification where source_key in
                               (select 'POSITION_CLOSED:' || journal_id from trading_journal where pair_id = ?
                                union all
                                select 'PLAN_INVALIDATED:' || plan_id from trading_plan where pair_id = ?)""").params(pair, pair).update();
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

    /**
     * R5: each filled plan's fill and each invalidated plan's invalidation is called once, and no plan gets the other
     * call; the plan whose fill failed once more.
     */
    private void assertOneStoreCallPerPlan(Run run, UUID failedOnce) {
        Map<UUID, Integer> fillCalls = calls(run, "fill");
        Map<UUID, Integer> invalidateCalls = calls(run, "invalidate");
        run.ids().forEach((label, id) -> {
            PlanStatus status = EXPECTED.get(label).status();
            int expected = status == PlanStatus.EXECUTED ? 1 : 0;
            if (id.equals(failedOnce)) {
                expected = 2;
            }
            assertThat(fillCalls.getOrDefault(id, 0))
                    .as("fill calls of %s", label)
                    .isEqualTo(expected);
            assertThat(invalidateCalls.getOrDefault(id, 0))
                    .as("invalidate calls of %s", label)
                    .isEqualTo(status == PlanStatus.INVALIDATED ? 1 : 0);
        });
    }

    private static Map<UUID, Integer> calls(Run run, String method) {
        Map<UUID, Integer> calls = new HashMap<>();
        run.invocations().stream()
                .filter(call -> call.getMethod().getName().equals(method))
                .forEach(call -> calls.merge(((PlanOutcome) call.getArgument(0)).planId(), 1, Integer::sum));
        return calls;
    }

    private Map<String, Outcome> outcomes(Map<String, UUID> ids) {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        ids.forEach((label, id) -> {
            TradingPlan plan = plan(id);
            if (plan.getExecutedAt() != null) {
                assertThat(plan.getExecutedAt())
                        .as("%s is never filled by a candle that opened before its activation", label)
                        .isAfterOrEqualTo(plan.getActivatedAt());
                assertThat(plan.getFillLiquidity())
                        .as("%s keeps the liquidity role the engine decided", label)
                        .isEqualTo(fills.get(id).liquidity());
            }
            if (plan.getInvalidatedAt() != null) {
                assertThat(plan.getInvalidatedAt())
                        .as("%s is never invalidated by a candle that opened before its activation", label)
                        .isAfterOrEqualTo(plan.getActivatedAt())
                        .isEqualTo(invalidations.get(id).invalidatedAt());
            }
            outcomes.put(
                    label,
                    new Outcome(
                            plan.getStatus(),
                            plan.getFillPrice() == null
                                    ? null
                                    : plan.getFillPrice().stripTrailingZeros(),
                            plan.getExecutedAt(),
                            plan.getFillLiquidity(),
                            plan.getInvalidationReason(),
                            plan.getInvalidatedAt()));
        });
        return outcomes;
    }

    /** A LIMIT plan at {@code leverage}, its stop loss 5 and its take profit 10 away, ACTIVE since {@code at}. */
    private TradingPlan leveraged(Direction direction, String entry, int leverage, Instant at) {
        BigDecimal price = new BigDecimal(entry);
        PlanFixture fixture = PlanFixture.futuresLong()
                .entry(entry)
                .stop(price.subtract(BigDecimal.valueOf(5)).toPlainString())
                .takeProfit(price.add(BigDecimal.TEN).toPlainString())
                .leverage(leverage)
                .profile("1", leverage);
        TradingPlan plan = TradingPlan.draft(
                SEEDED_ACCOUNT,
                pair,
                fixture.build(),
                new PlanDetails(
                        EntryType.LIMIT, at.plus(Duration.ofDays(7)), null, null, null, AnalysisTimeframe.ONE_HOUR),
                List.of());
        plan.activate(fixture.build(), List.of(), at);
        return plan;
    }

    /** A LIMIT plan ACTIVE in the database before the engine starts, its stop loss 5 and its take profit 10 away. */
    private UUID activePlan(Direction direction, String entry, Instant activatedAt) {
        return inTransaction(() -> plans.save(plan(direction, entry, activatedAt)))
                .getId();
    }

    /** A LIMIT or STOP plan ACTIVE in the database before the engine starts. */
    private UUID activePlan(
            Direction direction,
            EntryType entryType,
            String entry,
            String stopLoss,
            String takeProfit,
            String invalidation,
            Instant activatedAt) {
        return inTransaction(() ->
                        plans.save(plan(direction, entryType, entry, stopLoss, takeProfit, invalidation, activatedAt)))
                .getId();
    }

    /** A LIMIT plan activated while the engine runs: saved, and its activation published on commit. */
    private UUID activate(Direction direction, String entry, Instant activatedAt) {
        return activated(plan(direction, entry, activatedAt));
    }

    /** A LIMIT or STOP plan activated while the engine runs. */
    private UUID activate(
            Direction direction,
            EntryType entryType,
            String entry,
            String stopLoss,
            String takeProfit,
            String invalidation,
            Instant activatedAt) {
        return activated(plan(direction, entryType, entry, stopLoss, takeProfit, invalidation, activatedAt));
    }

    private UUID activated(TradingPlan unsaved) {
        return inTransaction(() -> {
                    TradingPlan plan = plans.save(unsaved);
                    events.publishEvent(new TradingPlanActivated(
                            plan.getId(),
                            plan.getUserId(),
                            plan.getMarket(),
                            pair,
                            plan.getDirection(),
                            plan.getEntryType(),
                            plan.getEntryPrice(),
                            plan.getStopLoss(),
                            plan.getTakeProfit(),
                            plan.getInvalidationPrice(),
                            plan.getActivatedAt()));
                    return plan;
                })
                .getId();
    }

    private TradingPlan plan(Direction direction, String entry, Instant activatedAt) {
        BigDecimal price = new BigDecimal(entry);
        boolean isLong = direction == Direction.LONG;
        return plan(
                direction,
                EntryType.LIMIT,
                entry,
                (isLong ? price.subtract(BigDecimal.valueOf(5)) : price.add(BigDecimal.valueOf(5))).toPlainString(),
                (isLong ? price.add(BigDecimal.TEN) : price.subtract(BigDecimal.TEN)).toPlainString(),
                null,
                activatedAt);
    }

    private TradingPlan plan(
            Direction direction,
            EntryType entryType,
            String entry,
            String stopLoss,
            String takeProfit,
            String invalidation,
            Instant activatedAt) {
        PlanFixture fixture = (direction == Direction.LONG ? PlanFixture.futuresLong() : PlanFixture.futuresShort())
                .entry(entry)
                .stop(stopLoss)
                .takeProfit(takeProfit);
        TradingPlan plan = TradingPlan.draft(
                SEEDED_ACCOUNT,
                pair,
                fixture.build(),
                new PlanDetails(
                        entryType,
                        activatedAt.plus(Duration.ofDays(7)),
                        null,
                        invalidation == null ? null : new BigDecimal(invalidation),
                        null,
                        AnalysisTimeframe.ONE_HOUR),
                List.of());
        plan.activate(fixture.build(), List.of(), activatedAt);
        return plan;
    }

    private TradingPlan plan(UUID id) {
        return inTransaction(() -> plans.findById(id).orElseThrow());
    }

    private void awaitWatermark(Instant openTime) {
        awaitUntil(
                () -> Optional.of(openTime).equals(matching.watermark(MarketType.FUTURES, pair)),
                "watermark " + openTime);
    }

    /**
     * Waits until every plan that the candles up to {@code minute} decide, except {@code skipped}, is no longer ACTIVE
     * in the database. One candle can decide several plans, stored one after another in an order the books do not
     * fix, so waiting for one of them would let a stop interrupt the store of another. It reads the committed rows,
     * not {@link #fills} or {@link #invalidations}: the spy records an outcome before its transaction commits.
     */
    private void awaitStoredUpTo(Map<String, UUID> ids, Instant minute, UUID skipped) {
        List<UUID> decided = ids.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(skipped))
                .filter(entry -> {
                    Instant at = EXPECTED.get(entry.getKey()).decidedAt();
                    return at != null && !at.isAfter(minute);
                })
                .map(Map.Entry::getValue)
                .toList();
        awaitUntil(
                () -> jdbc.sql("select count(*) from trading_plan where plan_id in (:ids) and plan_status = 'ACTIVE'")
                                .param("ids", decided)
                                .query(Long.class)
                                .single()
                        == 0,
                "outcomes up to " + minute + " committed");
    }

    private void awaitIdle() {
        awaitUntil(feed::isIdle, "replays done");
    }

    /**
     * Waits for the replay threads with a bound, polling the condition rather than sleeping in a loop.
     *
     * <p>Reference: Freeman, S., &amp; Pryce, N. (2009). <i>Growing Object-Oriented Software, Guided by Tests</i>.
     * Addison-Wesley, ch. 27 (testing asynchronous code); Luo, Q., Hariri, F., Eloussi, L., &amp; Marinov, D. (2014).
     * An Empirical Analysis of Flaky Tests. FSE 2014.
     */
    private static void awaitUntil(Supplier<Boolean> condition, String what) {
        await(what)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(condition::get);
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
                MarketType.FUTURES,
                pair,
                minute(m),
                low,
                high,
                high,
                false,
                minute(m).plusSeconds(SECONDS[i]),
                new BigDecimal(PATHS[m][0]));
    }

    /** The streamed mark price of minute {@code m}, 30 seconds into it. */
    private MarkPrice markPrice(int m) {
        return new MarkPrice(pair, new BigDecimal(MARKS[m]), minute(m).plusSeconds(30));
    }

    /** The closed mark price candle of minute {@code m}, of its one mark price. */
    private MinuteKline closedMark(int m) {
        BigDecimal mark = new BigDecimal(MARKS[m]);
        return new MinuteKline(
                MarketType.FUTURES,
                pair,
                minute(m),
                mark,
                mark,
                mark,
                true,
                minute(m).plusSeconds(60).minusMillis(1),
                mark);
    }

    private MinuteKline closedCandle(int m) {
        MinuteKline last = update(m, SECONDS.length - 1);
        return new MinuteKline(
                MarketType.FUTURES,
                pair,
                minute(m),
                last.low(),
                last.high(),
                last.close(),
                true,
                minute(m).plusSeconds(60).minusMillis(1),
                last.open());
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

    /** An administrator account of its own, so its audit rows can be removed without touching others. */
    private UUID insertAdministrator() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, id + "@replay.invalid", UserRole.ADMIN.name(), now, now)
                .update();
        return id;
    }

    /** Changes {@code SLIPPAGE_BPS} as an administrator does on SCR-43, which evicts the reader's cached value. */
    private void changeSlippage(String basisPoints) throws Exception {
        mvc.perform(put("/api/v1/admin/settings/SLIPPAGE_BPS")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"" + basisPoints + "\"}"))
                .andExpect(status().isOk());
    }

    /** An access token of {@link #admin}, timed by the injected clock that the token validator reads. */
    private String adminBearer() {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(admin.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.ADMIN.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
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

    /** A run: the plans by label, what it left them and their journal rows with, and every call on the service. */
    private record Run(
            Map<String, UUID> ids,
            Map<String, Outcome> outcomes,
            Map<String, JournalRow> journal,
            List<Invocation> invocations) {}

    /** What a run left a journal row with. */
    private record JournalRow(
            String status,
            String closeReason,
            BigDecimal exitPrice,
            Instant exitTime,
            BigDecimal realizedPnl,
            BigDecimal feeAmount,
            BigDecimal fundingFeeAmount,
            boolean fundingIncomplete) {}

    /** How a position closed. */
    private record Exited(CloseReason reason, BigDecimal price, Instant at) {}

    /** What a run left a plan with. */
    private record Outcome(
            PlanStatus status,
            BigDecimal fillPrice,
            Instant executedAt,
            LiquidityRole fillLiquidity,
            InvalidationReason invalidationReason,
            Instant invalidatedAt) {

        /** A resting LIMIT reached by a candle: maker, at its own price. */
        static Outcome filled(String price, Instant at) {
            return new Outcome(
                    PlanStatus.EXECUTED,
                    new BigDecimal(price).stripTrailingZeros(),
                    at,
                    LiquidityRole.MAKER,
                    null,
                    null);
        }

        /** A triggered STOP: taker, at the slipped price. */
        static Outcome takerFilled(String price, Instant at) {
            return new Outcome(
                    PlanStatus.EXECUTED,
                    new BigDecimal(price).stripTrailingZeros(),
                    at,
                    LiquidityRole.TAKER,
                    null,
                    null);
        }

        static Outcome invalidated(InvalidationReason reason, Instant at) {
            return new Outcome(PlanStatus.INVALIDATED, null, null, null, reason, at);
        }

        static Outcome active() {
            return new Outcome(PlanStatus.ACTIVE, null, null, null, null, null);
        }

        /** The open time of the candle that filled or invalidated the plan, or {@code null} when none did. */
        Instant decidedAt() {
            return executedAt != null ? executedAt : invalidatedAt;
        }
    }
}
