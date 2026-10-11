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
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Many threads change the same alerts in the database and send the engine their {@link AlertsChanged}, so the reads
 * are applied in any order: edits, pauses, resumes, deletes, and moves to another market or pair. Every fourth read
 * is held back until two more changes have been made, as a slow read would be. Once every change and its read have
 * returned, and before any further read, each alert is in the book the database names, at its version, and in no
 * other; a paused or deleted one is in none.
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
    private static final int ALERTS = 6;
    private static final int THREADS = 6;
    private static final int CHANGES = 300;
    private static final List<UUID> PAIRS = List.of(UUID.randomUUID(), UUID.randomUUID());

    private AlertEngine engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
    }

    @RepeatedTest(20)
    void NSF06_concurrentChangesAndLateReads_leaveEachAlertInTheBookTheDatabaseNames_beforeAnyFurtherRead()
            throws Exception {
        long seed = System.nanoTime();
        StoredAlerts stored = new StoredAlerts(THREADS);
        List<UUID> ids = new CopyOnWriteArrayList<>();
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
                Random random = new Random(seed + t);
                done.add(pool.submit(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < CHANGES; i++) {
                            Set<UUID> changed = stored.change(pick(ids, random), random);
                            changed.stream().filter(id -> !ids.contains(id)).forEach(ids::add);
                            engine.onAlertsChanged(new AlertsChanged(changed));
                        }
                    } finally {
                        stored.leave();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> each : done) {
                each.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (UUID id : ids) {
            assertThat(engine.booksHolding(id))
                    .as("seed %d: books of %s", seed, id)
                    .isEqualTo(stored.expected(id));
            assertThat(engine.locatedAt(id))
                    .as("seed %d: located of %s", seed, id)
                    .isEqualTo(stored.expected(id));
        }
    }

    /** Mostly one of the newest alerts; now and then any, deleted ones included. */
    private static UUID pick(List<UUID> ids, Random random) {
        int size = ids.size();
        return random.nextInt(10) == 0
                ? ids.get(random.nextInt(size))
                : ids.get(size - 1 - random.nextInt(Math.min(ALERTS, size)));
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
        private final AtomicLong reads = new AtomicLong();

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changedOrAllWaiting = lock.newCondition();
        private long changes;
        private int active;
        private int waiting;

        StoredAlerts(int threads) {
            this.active = threads;
        }

        UUID insert() {
            UUID id = UUID.randomUUID();
            rows.put(id, new Row(alert(id, MarketType.SPOT, PAIRS.getFirst(), "100", 1), true));
            return id;
        }

        /** One change of the alert, if it still exists; returns the alerts it changed. */
        Set<UUID> change(UUID id, Random random) {
            int op = random.nextInt(25);
            Set<UUID> changed;
            if (op == 0 && rows.remove(id) != null) {
                // A delete; another alert is created so that some keep living.
                changed = Set.of(id, insert());
            } else {
                rows.computeIfPresent(id, (key, row) -> next(row, op, random));
                changed = Set.of(id);
            }
            lock.lock();
            try {
                changes++;
                changedOrAllWaiting.signalAll();
            } finally {
                lock.unlock();
            }
            return changed;
        }

        private static Row next(Row row, int op, Random random) {
            PriceAlert now = row.alert();
            UUID id = now.alertId();
            long version = now.version() + 1;
            return switch (op % 4) {
                case 0 ->
                    new Row(
                            alert(id, now.market(), now.pairId(), String.valueOf(random.nextInt(50, 150)), version),
                            row.active());
                case 1 -> new Row(alert(id, other(now.market()), now.pairId(), "100", version), row.active());
                case 2 -> new Row(alert(id, now.market(), other(now.pairId()), "100", version), row.active());
                default -> new Row(alert(id, now.market(), now.pairId(), "100", version), !row.active());
            };
        }

        /** A changing thread is done; the reads it might have been waiting for will not come. */
        void leave() {
            lock.lock();
            try {
                active--;
                changedOrAllWaiting.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** What the books should hold for the alert: its pair's book at its version, or nothing. */
        Map<AlertEngine.PairKey, Long> expected(UUID id) {
            return stored(id)
                    .map(alert -> Map.of(new AlertEngine.PairKey(alert.market(), alert.pairId()), alert.version()))
                    .orElse(Map.of());
        }

        @Override
        public List<PriceAlert> activePriceAlerts() {
            return rows.keySet().stream().flatMap(id -> stored(id).stream()).toList();
        }

        /** Reads the row now; every fourth read returns only after two more changes, or once no change can come. */
        @Override
        public Optional<PriceAlert> activePriceAlert(UUID alertId) {
            Optional<PriceAlert> read = stored(alertId);
            if (reads.incrementAndGet() % 4 == 0) {
                awaitChanges(2);
            }
            return read;
        }

        private void awaitChanges(int count) {
            lock.lock();
            try {
                long target = changes + count;
                waiting++;
                changedOrAllWaiting.signalAll();
                while (changes < target && waiting < active) {
                    changedOrAllWaiting.await();
                }
                waiting--;
            } catch (InterruptedException interrupted) {
                waiting--;
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private Optional<PriceAlert> stored(UUID alertId) {
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
