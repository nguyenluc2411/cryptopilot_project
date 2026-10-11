package com.cryptopilot.watchlist.job;

import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.calculator.PriceAlertBook;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * NSF-06 for PRICE alerts: evaluates the last price of every streamed pair against the pair's {@link PriceAlertBook}
 * and records the triggers through {@link AlertTriggerService}.
 *
 * <ul>
 *   <li>The stream thread never waits: {@link #onMinuteKline} keeps the candle's close as the pair's latest price and
 *       queues the pair once in its partition (by market and pair). A full queue leaves the pair outside it, still
 *       with only its latest price, and logs a warning at most once a minute.
 *   <li>One thread per partition evaluates its pairs, at most once per {@code throttle} each, on the latest price.
 *   <li>At start the books are loaded from the ACTIVE PRICE alerts; INDICATOR alerts are not evaluated yet. After each
 *       committed change of an alert ({@link AlertsChanged}) the alert is read again and added, replaced or removed.
 *       A trigger the database refuses drops that rule from the book that fired it, then reads the alert again.
 *   <li>An alert is in at most one book, and {@code located} names that book and the version it holds. Every change
 *       of an alert's place happens inside {@code located.compute} on the alert's entry, so the two agree whenever
 *       no change is running, and a read older than the version held is ignored even when it names another book.
 *       A move removes the rule from the old book before it puts it in the new one: for that instant the alert is
 *       in neither book, never in both. The database read stays outside the critical section.
 *   <li>When a market's stream reconnects, previous prices older than {@code staleness} are forgotten.
 * </ul>
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-87, D-88, D-89.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, §2.3 (compound
 * actions), §4.4 (adding functionality to thread-safe classes), ch. 5.3 (bounded producer-consumer queues).
 * Herlihy, M. P. &amp; Wing, J. M. (1990). Linearizability: A Correctness Condition for Concurrent Objects. <i>ACM
 * TOPLAS</i>, 12(3) (each change of an alert takes effect at one point, inside its compute). Hohpe, G. &amp;
 * Woolf, B. (2003). <i>Enterprise Integration Patterns</i>.
 * Addison-Wesley, "Aggregator" (only the latest price of a pair is kept while it waits).
 */
@Component
public class AlertEngine implements MinuteKlineListener {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    private static final long WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int REFUSALS_BEFORE_WARN = 3;

    private final AlertTriggerService triggers;
    private final AlertEngineProperties properties;
    private final Clock clock;
    private final ThreadFactory threads;

    private final Map<PairKey, PriceAlertBook> books = new ConcurrentHashMap<>();

    /** The book that holds each alert and the version it holds; changed only inside {@code compute} on its entry. */
    private final Map<UUID, Location> located = new ConcurrentHashMap<>();

    /** Refusals in a row per alert, for the warning of {@link #countRefusal}. */
    private final Map<UUID, Refusals> refusals = new ConcurrentHashMap<>();

    private final AtomicLong lastOverflowWarning = new AtomicLong(System.nanoTime() - WARN_EVERY_NANOS);

    private volatile List<Partition> partitions = List.of();
    private volatile boolean running;

    @Autowired
    public AlertEngine(AlertTriggerService triggers, AlertEngineProperties properties, Clock clock) {
        this(triggers, properties, clock, Thread.ofVirtual().factory());
    }

    AlertEngine(AlertTriggerService triggers, AlertEngineProperties properties, Clock clock, ThreadFactory threads) {
        this.triggers = triggers;
        this.properties = properties;
        this.clock = clock;
        this.threads = threads;
    }

    /** Loads the books and starts one thread per partition. A second call does nothing. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.enabled() || running) {
            return;
        }
        List<Partition> fresh = new ArrayList<>();
        for (int i = 0; i < properties.partitions(); i++) {
            fresh.add(new Partition(properties.queueCapacity()));
        }
        partitions = List.copyOf(fresh);
        // A refresh that ran into the last stop may have left an entry, which would hide its alert from the load.
        books.clear();
        located.clear();
        // Before the load, so a change committed during it is applied as well; both read the stored state.
        running = true;
        List<PriceAlert> loaded = triggers.activePriceAlerts();
        loaded.forEach(this::hold);
        log.debug("NSF-06 {} ACTIVE INDICATOR alerts are not evaluated yet", triggers.activeAlertsNotEvaluated());
        for (int i = 0; i < fresh.size(); i++) {
            Thread thread = threads.newThread(fresh.get(i)::run);
            thread.setName("watchlist-alert-" + i);
            thread.start();
            fresh.get(i).thread = thread;
        }
        log.info("NSF-06 alert engine started with {} PRICE alerts in {} partitions", loaded.size(), fresh.size());
    }

    /** Stops the partitions; the books are loaded again at the next start. */
    @PreDestroy
    public synchronized void stop() {
        running = false;
        partitions.forEach(Partition::stop);
        long deadline = System.nanoTime() + properties.stopTimeout().toNanos();
        for (Partition partition : partitions) {
            try {
                if (partition.thread != null) {
                    partition.thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        books.clear();
        located.clear();
        refusals.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /** Whether the engine holds this alert. */
    public boolean holds(UUID alertId) {
        return located.containsKey(alertId);
    }

    /** Where {@code located} puts the alert, with the version; empty when nowhere. For the tests of this package. */
    Map<PairKey, Long> locatedAt(UUID alertId) {
        Location location = located.get(alertId);
        return location == null ? Map.of() : Map.of(location.key(), location.version());
    }

    /** Every book that holds the alert, with the version it holds. For the tests of this package. */
    Map<PairKey, Long> booksHolding(UUID alertId) {
        Map<PairKey, Long> holding = new HashMap<>();
        books.forEach((key, book) -> book.versionOf(alertId).ifPresent(version -> holding.put(key, version)));
        return holding;
    }

    /** Called on a stream thread: keeps the close as the pair's latest price and queues the pair. Never blocks. */
    @Override
    public void onMinuteKline(MinuteKline kline) {
        if (!running) {
            return;
        }
        PairKey key = new PairKey(kline.market(), kline.pairId());
        if (bookOf(key).offer(kline.close(), kline.eventTime())) {
            partitionOf(key).signal(key);
        }
    }

    /** Reads each changed alert again after the commit, and holds it or lets it go. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertsChanged(AlertsChanged event) {
        if (running) {
            event.alertIds().forEach(this::refresh);
        }
    }

    /** A reconnected stream may have missed prices: a previous price older than the staleness is forgotten. */
    @EventListener
    public void onStreamReconnected(MarketStreamReconnected event) {
        Instant cutoff = clock.instant().minus(properties.staleness());
        books.forEach((key, book) -> {
            if (key.market() == event.market()) {
                book.forgetPreviousBefore(cutoff);
            }
        });
    }

    private void refresh(UUID alertId) {
        try {
            // Taken before the read: a rule another refresh puts meanwhile is newer and must not be removed.
            Location before = located.get(alertId);
            long seen = before == null ? -1 : before.version();
            Optional<PriceAlert> stored = triggers.activePriceAlert(alertId);
            if (stored.isPresent()) {
                hold(stored.get());
                return;
            }
            refusals.remove(alertId);
            located.computeIfPresent(alertId, (id, current) -> {
                if (current.version() > seen) {
                    return current;
                }
                bookOf(current.key()).removeIfVersionAtMost(id, seen);
                return heldIn(current.key(), id);
            });
        } catch (RuntimeException failure) {
            log.warn("NSF-06 alert {} could not be read again: {}", alertId, failure.toString());
        }
    }

    /** Puts the alert in its pair's book unless the version held is as new; a move leaves the old book first. */
    private void hold(PriceAlert alert) {
        PairKey key = keyOf(alert);
        located.compute(alert.alertId(), (id, current) -> {
            if (current != null && current.version() >= alert.version()) {
                return current;
            }
            if (current != null && !current.key().equals(key)) {
                bookOf(current.key()).remove(id);
            }
            bookOf(key).put(alert);
            return heldIn(key, id);
        });
    }

    /** What {@code located} says once the book at {@code key} has changed: the version it holds, or nothing. */
    private Location heldIn(PairKey key, UUID alertId) {
        PriceAlertBook book = books.get(key);
        OptionalLong version = book == null ? OptionalLong.empty() : book.versionOf(alertId);
        return version.isPresent() ? new Location(key, version.getAsLong()) : null;
    }

    /** A change the partition makes to one book, applied under the alert's entry like every other change. */
    private void changeInBook(PairKey key, UUID alertId, Runnable change) {
        located.compute(alertId, (id, current) -> {
            change.run();
            // Moved by a refresh meanwhile: this book no longer holds it, so the entry stays as it is.
            return current != null && !current.key().equals(key) ? current : heldIn(key, id);
        });
    }

    private void evaluate(PairKey key) {
        PriceAlertBook book = books.get(key);
        if (book == null) {
            return;
        }
        for (PriceAlertHit hit : book.evaluate()) {
            try {
                UUID alertId = hit.alert().alertId();
                if (triggers.tryTrigger(hit)) {
                    refusals.remove(alertId);
                    changeInBook(key, alertId, () -> book.triggered(hit));
                } else {
                    countRefusal(alertId);
                    // The read below puts it back if the row is still ACTIVE.
                    changeInBook(
                            key,
                            alertId,
                            () -> book.discardIfVersionAtMost(
                                    alertId, hit.alert().version()));
                    refresh(alertId);
                }
            } catch (RuntimeException failure) {
                log.warn("NSF-06 alert {} trigger failed: {}", hit.alert().alertId(), failure.toString());
            }
        }
    }

    /** Warns, at most once a minute per alert, when the database keeps refusing an alert's triggers. */
    private void countRefusal(UUID alertId) {
        Instant now = clock.instant();
        Refusals next = refusals.merge(
                alertId, new Refusals(1, null), (old, one) -> new Refusals(old.count() + 1, old.warnedAt()));
        if (next.count() >= REFUSALS_BEFORE_WARN
                && (next.warnedAt() == null || !now.isBefore(next.warnedAt().plus(Duration.ofMinutes(1))))) {
            refusals.put(alertId, new Refusals(next.count(), now));
            log.warn("NSF-06 alert {} refused by the database {} times in a row", alertId, next.count());
        }
    }

    private PriceAlertBook bookOf(PairKey key) {
        return books.computeIfAbsent(key, this::newBook);
    }

    private PriceAlertBook newBook(PairKey ignored) {
        return new PriceAlertBook(clock, properties.throttle(), properties.tombstoneTtl());
    }

    private Partition partitionOf(PairKey key) {
        List<Partition> current = partitions;
        return current.get(Math.floorMod(key.hashCode(), current.size()));
    }

    private static PairKey keyOf(PriceAlert alert) {
        return new PairKey(alert.market(), alert.pairId());
    }

    private void warnOverflow() {
        long now = System.nanoTime();
        long last = lastOverflowWarning.get();
        if (now - last >= WARN_EVERY_NANOS && lastOverflowWarning.compareAndSet(last, now)) {
            log.warn("NSF-06 an alert partition's queue is full; pairs wait with their latest price only");
        }
    }

    /** One partition: a bounded queue of pairs with a price to evaluate, drained by one thread. */
    private final class Partition {

        private final BlockingQueue<PairKey> queue;

        /** Pairs in the queue or waiting outside it; a pair is queued once however many prices it receives. */
        private final Set<PairKey> signalled = ConcurrentHashMap.newKeySet();

        /** Pairs signalled while the queue was full. */
        private final Set<PairKey> overflow = ConcurrentHashMap.newKeySet();

        private volatile boolean stopped;
        private Thread thread;

        Partition(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        void signal(PairKey key) {
            if (signalled.add(key) && !queue.offer(key)) {
                overflow.add(key);
                warnOverflow();
            }
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        void run() {
            // Pairs whose latest price arrived inside the throttle: evaluated again once the throttle has passed.
            Set<PairKey> throttled = new HashSet<>();
            while (!stopped) {
                PairKey key;
                try {
                    key = queue.poll(properties.throttle().toNanos(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (key != null) {
                        signalled.remove(key);
                        evaluateAndTrack(key, throttled);
                    }
                    for (PairKey waiting : List.copyOf(throttled)) {
                        evaluateAndTrack(waiting, throttled);
                    }
                    refill();
                } catch (RuntimeException unexpected) {
                    log.error("NSF-06 alert partition failed on {}; it carries on", key, unexpected);
                }
            }
        }

        private void evaluateAndTrack(PairKey key, Set<PairKey> throttled) {
            evaluate(key);
            PriceAlertBook book = books.get(key);
            if (book != null && book.hasPending()) {
                throttled.add(key);
            } else {
                throttled.remove(key);
            }
        }

        /** Moves pairs that waited outside the full queue into it, as room allows. */
        private void refill() {
            for (Iterator<PairKey> it = overflow.iterator(); it.hasNext(); ) {
                PairKey key = it.next();
                if (!queue.offer(key)) {
                    return;
                }
                it.remove();
            }
        }
    }

    private record Refusals(int count, Instant warnedAt) {}

    /** The book that holds an alert and the version it holds there. */
    private record Location(PairKey key, long version) {}

    /** A pair on one market. */
    record PairKey(MarketType market, UUID pairId) {

        PairKey {
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(pairId, "pairId");
        }
    }
}
