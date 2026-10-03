package com.cryptopilot.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.impl.MinuteKlineFeed;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * T-043 acceptance: a restart replay equals live processing. One recorded sequence of {@code kline_1m} updates — four
 * updates inside each minute, then the closed candle — runs twice against the real schema:
 *
 * <ul>
 *   <li>LIVE: every update goes through the live path;
 *   <li>REPLAY: the updates up to the middle of minute 5 go through the live path, the engine stops (a crash), and on
 *       restart the minutes from the watermark on are replayed from closed candles served by the exchange stub, three
 *       per page.
 * </ul>
 *
 * Two plans are activated during the live part, one in the middle of a minute whose low already reached its entry
 * (D-77). Both runs must leave every plan with the same status, fill price and {@code executed_at}, and no plan filled
 * by a candle that opened before its activation. No network: the exchange is a stub of {@link MarketApi}.
 *
 * <p>Rule: NSF-07, BR-33; A-04; D-77, D-78; ADR-011.
 */
@SpringBootTest(
        properties = {
            "cryptopilot.trading.matching.replay.enabled=true",
            "cryptopilot.trading.matching.replay.catch-up-attempts=0",
            "cryptopilot.trading.matching.replay.catch-up-wait=0s"
        })
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class MatchingReplayTest {

    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    /** The first minute of the recording; the fixed clock stands 30 minutes after it, inside the replay window. */
    private static final Instant M0 = FixedClockConfig.NOW.truncatedTo(ChronoUnit.HOURS);

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
        {"98", "99", "98.5", "98.5"}
    };

    @MockitoBean
    private MarketApi market;

    @MockitoSpyBean
    private MatchingService matching;

    @Autowired
    private MatchingWorker worker;

    @Autowired
    private MinuteKlineFeed feed;

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    /** The closed candles the exchange stub serves; empty until the restart of the REPLAY run. */
    private final List<MinuteKline> served = new CopyOnWriteArrayList<>();

    private final Map<UUID, Fill> fills = new ConcurrentHashMap<>();
    private final List<Instant> pagesAskedFrom = new CopyOnWriteArrayList<>();

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
            return MinuteKlineBatch.of(served.stream()
                    .filter(kline -> !kline.openTime().isBefore(from))
                    .limit(3)
                    .toList());
        });
        doAnswer(call -> {
                    boolean filled = (boolean) call.callRealMethod();
                    if (filled) {
                        Fill fill = call.getArgument(0);
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
    void T043_aRestartReplay_fillsEveryPlanAsLiveProcessingDoes() {
        Map<String, Outcome> live = run(false);
        resetRun();
        Map<String, Outcome> replayed = run(true);

        assertThat(replayed).isEqualTo(live);
        assertThat(live)
                .containsEntry("long98", Outcome.filled("98", minute(1)))
                .containsEntry("short105", Outcome.filled("105", minute(6)))
                .containsEntry("long99MidMinute3", Outcome.filled("99", minute(5)))
                .containsEntry("short103AtMinute4", Outcome.filled("103", minute(4)))
                .containsEntry("long97", Outcome.filled("97", minute(8)))
                .containsEntry("long90", Outcome.active());
        assertThat(pagesAskedFrom)
                .as("the restart replays from the minute after the watermark, three candles a page")
                .containsSubsequence(minute(5), minute(8), minute(10));
    }

    @Test
    void A04_theWatermark_isTheLastClosedCandleFullyMatched_andNeverMovesBack() {
        worker.start();
        UUID unrelated = UUID.randomUUID();
        try {
            matching.advanceWatermark(MarketType.FUTURES, pair, minute(4));
            matching.advanceWatermark(MarketType.FUTURES, pair, minute(2));
            matching.advanceWatermark(MarketType.SPOT, pair, minute(7));

            assertThat(matching.watermark(MarketType.FUTURES, pair)).contains(minute(4));
            assertThat(matching.watermark(MarketType.SPOT, pair)).contains(minute(7));
            assertThat(matching.watermark(MarketType.FUTURES, unrelated)).isEmpty();
        } finally {
            jdbc.sql("delete from matching_watermark where pair_id = ?")
                    .param(pair)
                    .update();
        }
    }

    /**
     * One run of the recording. {@code crash}: stop after the middle of minute 5 and restart with the exchange serving
     * every closed candle; otherwise the whole recording goes through the live path.
     */
    private Map<String, Outcome> run(boolean crash) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        ids.put("long98", activePlan(Direction.LONG, "98", BEFORE));
        ids.put("short105", activePlan(Direction.SHORT, "105", BEFORE));
        ids.put("long97", activePlan(Direction.LONG, "97", BEFORE));
        ids.put("long90", activePlan(Direction.LONG, "90", BEFORE));
        feed.start();

        for (int m = 0; m < PATHS.length; m++) {
            Instant open = minute(m);
            if (m == 4) {
                ids.put("short103AtMinute4", activate(Direction.SHORT, "103", open));
            }
            for (int i = 0; i < SECONDS.length; i++) {
                if (m == 3 && i == 2) {
                    ids.put("long99MidMinute3", activate(Direction.LONG, "99", open.plusSeconds(30)));
                }
                if (crash && m == 5 && i == 2) {
                    awaitWatermark(minute(4));
                    feed.stop();
                    worker.stop();
                    served.addAll(closedCandles());
                    feed.start();
                    awaitWatermark(minute(9));
                    return outcomes(ids);
                }
                feed.onMinuteKline(update(m, i));
            }
            feed.onMinuteKline(closedCandle(m));
        }
        awaitWatermark(minute(9));
        return outcomes(ids);
    }

    private void resetRun() {
        feed.stop();
        worker.stop();
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from matching_watermark where pair_id = ?").param(pair).update();
        served.clear();
        fills.clear();
        pagesAskedFrom.clear();
    }

    private Map<String, Outcome> outcomes(Map<String, UUID> ids) {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        ids.forEach((label, id) -> {
            TradingPlan plan = plan(id);
            if (plan.getExecutedAt() != null) {
                assertThat(plan.getExecutedAt())
                        .as("%s is never filled by a candle that opened before its activation", label)
                        .isAfterOrEqualTo(plan.getActivatedAt());
            }
            Fill fill = fills.get(id);
            outcomes.put(
                    label,
                    new Outcome(
                            plan.getStatus(),
                            fill == null ? null : fill.price().stripTrailingZeros(),
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
        Instant deadline = Instant.now().plusSeconds(20);
        while (!Optional.of(openTime).equals(matching.watermark(MarketType.FUTURES, pair))) {
            assertThat(Instant.now())
                    .as("watermark %s reached in time", openTime)
                    .isBefore(deadline);
            Thread.onSpinWait();
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

    private List<MinuteKline> closedCandles() {
        List<MinuteKline> candles = new ArrayList<>();
        for (int m = 0; m < PATHS.length; m++) {
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
