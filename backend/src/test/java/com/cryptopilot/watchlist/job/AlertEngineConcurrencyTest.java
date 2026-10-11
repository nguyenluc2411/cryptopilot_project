package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Many threads change the same alerts in the database and send the engine their {@link AlertsChanged}, so the reads
 * are applied in any order: edits, pauses, resumes and moves to another market or pair. Whenever no change is
 * running, each alert is in at most one book and {@code located} names exactly that book and version; after a last
 * read of every alert, the books hold what the database holds.
 *
 * <p>The partitions never run: only the changes touch the books.
 *
 * <p>Rule: NSF-06; TECHNICAL_DESIGN 7.9.
 *
 * <p>Reference: Herlihy, M. P. &amp; Wing, J. M. (1990). Linearizability: A Correctness Condition for Concurrent
 * Objects. <i>ACM TOPLAS</i>, 12(3) (a state checked once the concurrent changes have returned).
 */
class AlertEngineConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-05T08:10:00Z");
    private static final int ALERTS = 4;
    private static final int THREADS = 6;
    private static final int CHANGES = 300;
    private static final List<UUID> PAIRS = List.of(UUID.randomUUID(), UUID.randomUUID());

    private final StoredAlerts stored = new StoredAlerts();
    private AlertEngine engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
    }

    @RepeatedTest(20)
    void NSF06_concurrentChangesOfTheSameAlerts_leaveEachInOneBook_theOneLocatedNames() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ALERTS; i++) {
            ids.add(stored.insert());
        }
        ThreadFactory idle = ignored -> Thread.ofVirtual().unstarted(() -> {});
        engine = new AlertEngine(stored, properties(), new MutableTestClock(T0), idle);
        engine.start();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                done.add(pool.submit(() -> {
                    go.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < CHANGES; i++) {
                        UUID id = ids.get(random.nextInt(ids.size()));
                        stored.change(id, random);
                        engine.onAlertsChanged(AlertsChanged.of(id));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> each : done) {
                each.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (UUID id : ids) {
            assertThat(engine.booksHolding(id)).as("books of %s", id).hasSizeLessThanOrEqualTo(1);
            assertThat(engine.booksHolding(id)).as("located of %s", id).isEqualTo(engine.locatedAt(id));
        }

        engine.onAlertsChanged(new AlertsChanged(Set.copyOf(ids)));
        for (UUID id : ids) {
            Map<AlertEngine.PairKey, Long> expected = stored.activePriceAlert(id)
                    .map(alert -> Map.of(new AlertEngine.PairKey(alert.market(), alert.pairId()), alert.version()))
                    .orElse(Map.of());
            assertThat(engine.locatedAt(id)).as("located of %s", id).isEqualTo(expected);
            assertThat(engine.booksHolding(id)).as("books of %s", id).isEqualTo(expected);
        }
    }

    private static AlertEngineProperties properties() {
        return new AlertEngineProperties(
                true,
                1,
                1000,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }

    /** The alert table, reduced to what the engine reads: each change is one new row version. */
    private static final class StoredAlerts implements AlertTriggerService {

        private final Map<UUID, Row> rows = new ConcurrentHashMap<>();

        UUID insert() {
            UUID id = UUID.randomUUID();
            rows.put(id, new Row(alert(id, MarketType.SPOT, PAIRS.getFirst(), "100", 1), true));
            return id;
        }

        void change(UUID id, ThreadLocalRandom random) {
            rows.compute(id, (key, row) -> {
                PriceAlert now = row.alert();
                long next = now.version() + 1;
                return switch (random.nextInt(4)) {
                    case 0 ->
                        new Row(
                                alert(id, now.market(), now.pairId(), String.valueOf(random.nextInt(50, 150)), next),
                                true);
                    case 1 -> new Row(alert(id, other(now.market()), now.pairId(), "100", next), row.active());
                    case 2 -> new Row(alert(id, now.market(), other(now.pairId()), "100", next), row.active());
                    default -> new Row(alert(id, now.market(), now.pairId(), "100", next), !row.active());
                };
            });
        }

        @Override
        public List<PriceAlert> activePriceAlerts() {
            return rows.keySet().stream()
                    .flatMap(id -> activePriceAlert(id).stream())
                    .toList();
        }

        @Override
        public Optional<PriceAlert> activePriceAlert(UUID alertId) {
            Row row = rows.get(alertId);
            return row != null && row.active() ? Optional.of(row.alert()) : Optional.empty();
        }

        @Override
        public long activeAlertsNotEvaluated() {
            return 0;
        }

        @Override
        public boolean tryTrigger(PriceAlertHit hit) {
            throw new AssertionError("the partitions do not run in this test");
        }

        private static MarketType other(MarketType market) {
            return market == MarketType.SPOT ? MarketType.FUTURES : MarketType.SPOT;
        }

        private static UUID other(UUID pair) {
            return pair.equals(PAIRS.getFirst()) ? PAIRS.getLast() : PAIRS.getFirst();
        }

        private static PriceAlert alert(UUID id, MarketType market, UUID pair, String threshold, long version) {
            return new PriceAlert(
                    id,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    pair,
                    "BTCUSDT",
                    market,
                    ConditionOperator.GREATER_THAN,
                    new BigDecimal(threshold),
                    TriggerMode.EVERY_TIME,
                    1,
                    null,
                    true,
                    false,
                    false,
                    0,
                    null,
                    null,
                    version);
        }

        private record Row(PriceAlert alert, boolean active) {}
    }
}
