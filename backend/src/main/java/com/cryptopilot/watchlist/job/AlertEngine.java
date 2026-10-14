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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
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
 *   <li>At start the books are loaded from the ACTIVE PRICE alerts; {@link IndicatorAlertJob} evaluates INDICATOR
 *       alerts. After each committed change of an alert ({@link AlertsChanged}) the alert is read again and added,
 *       replaced or removed.
 *       A trigger the database refuses drops that rule from the book that fired it, then reads the alert again.
 *   <li>An alert is in at most one book, and {@code located} names that book and the version it holds. Every change
 *       of an alert's place happens inside {@code located.compute} on the alert's entry, so the two agree whenever
 *       no change is running. A move removes the rule from the old book before it puts it in the new one: for that
 *       instant the alert is in neither book, never in both. The database read stays outside the critical section.
 *   <li>Reads are ordered by a ticket taken before the read is sent, not by what the read returns: a read that finds
 *       no ACTIVE alert carries no version. A result is applied only if its ticket is newer than the last one applied
 *       to the alert, and a rule found only if its version is also newer than the one held. A read that finds
 *       nothing leaves a removal marker, so an older read still in flight cannot put the alert back. A marker is
 *       forgotten once {@code tombstoneTtl} has passed and no read of the alert is in flight. A newer result refused
 *       because a read with a later ticket was applied first is not lost: every committed change publishes
 *       {@link AlertsChanged}, and the read it causes is issued after the commit, so it has the newest ticket and
 *       sees the newest state.
 *   <li>A read issued before the last {@link #start} or {@link #stop} is ignored: the load at start supersedes it.
 *   <li>When a market's stream reconnects, previous prices older than {@code staleness} are forgotten.
 * </ul>
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-87, D-88, D-89.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, §2.3 (compound
 * actions), §4.4 (adding functionality to thread-safe classes), ch. 5.3 (bounded producer-consumer queues).
 * Herlihy, M. P. &amp; Wing, J. M. (1990). Linearizability: A Correctness Condition for Concurrent Objects. <i>ACM
 * TOPLAS</i>, 12(3) (each change of an alert takes effect at one point, inside its compute). Lamport, L. (1978).
 * Time, Clocks, and the Ordering of Events in a Distributed System. <i>Communications of the ACM</i>, 21(7) (reads
 * ordered by a logical counter rather than by what they return). Hohpe, G. &amp;
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

    /** Each alert's book, version and reads; changed only inside {@code compute} on its entry. */
    private final Map<UUID, Location> located = new ConcurrentHashMap<>();

    /** The alerts given a removal marker, oldest first, so the expired markers can be forgotten. */
    private final Queue<UUID> marked = new ConcurrentLinkedQueue<>();

    /** The read tickets: one counter for all alerts. */
    private final AtomicLong tickets = new AtomicLong();

    /** A read with a ticket below this one was issued before the last start or stop, and is ignored. */
    private volatile long ticketFloor;

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
        // Before the clear: a read still in flight from the last run stays below the floor, and the load above it.
        long load = tickets.incrementAndGet();
        ticketFloor = load;
        books.clear();
        located.clear();
        marked.clear();
        // Before the load, so a change committed during it is applied as well; both read the stored state.
        running = true;
        List<PriceAlert> loaded = triggers.activePriceAlerts();
        loaded.forEach(alert -> apply(alert.alertId(), load, false, at -> held(alert.alertId(), at, alert, load)));
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
        ticketFloor = tickets.incrementAndGet();
        books.clear();
        located.clear();
        marked.clear();
        refusals.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /** Whether the engine holds this alert. */
    public boolean holds(UUID alertId) {
        Location location = located.get(alertId);
        return location != null && location.key() != null;
    }

    /** Where {@code located} puts the alert, with the version; empty when nowhere. For the tests of this package. */
    Map<PairKey, Long> locatedAt(UUID alertId) {
        Location location = located.get(alertId);
        return location == null || location.key() == null ? Map.of() : Map.of(location.key(), location.version());
    }

    /** Whether {@code located} has an entry for the alert, marker or not. For the tests of this package. */
    boolean tracks(UUID alertId) {
        return located.containsKey(alertId);
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
            forgetExpiredMarkers();
            long ticket = issue(alertId);
            Optional<PriceAlert> stored;
            try {
                stored = triggers.activePriceAlert(alertId);
            } catch (RuntimeException failure) {
                // Only ends the read: the entry stays as it is.
                apply(alertId, ticket, true, at -> at);
                throw failure;
            }
            if (stored.isEmpty()) {
                refusals.remove(alertId);
            }
            apply(alertId, ticket, true, at -> stored.map(alert -> held(alertId, at, alert, ticket))
                    .orElseGet(() -> removed(alertId, at, ticket)));
        } catch (RuntimeException failure) {
            log.warn("NSF-06 alert {} could not be read again: {}", alertId, failure.toString());
        }
    }

    /** Takes the ticket of a read about to be sent, and counts the read as in flight. */
    private long issue(UUID alertId) {
        long[] ticket = new long[1];
        located.compute(alertId, (id, current) -> {
            // Taken inside the compute: a stop or start that clears this entry leaves the ticket below its floor.
            ticket[0] = tickets.incrementAndGet();
            Location at = current == null ? Location.NONE : current;
            return at.reading(at.reading() + 1);
        });
        return ticket[0];
    }

    /**
     * Applies the result of a read under the alert's entry, unless a read with a later ticket was applied first.
     *
     * @param counted whether {@link #issue} counted the read in flight; the load at start is not counted
     */
    private void apply(UUID alertId, long ticket, boolean counted, UnaryOperator<Location> change) {
        located.compute(alertId, (id, current) -> {
            if (ticket < ticketFloor) {
                return current;
            }
            Location at = current == null ? Location.NONE : current;
            if (counted) {
                at = at.reading(Math.max(0, at.reading() - 1));
            }
            if (ticket <= at.applied()) {
                return settled(at);
            }
            // Newer than the marker: once expired, with no older read in flight, the marker guards nothing.
            at = Objects.requireNonNullElse(settled(at), Location.NONE);
            return settled(change.apply(at));
        });
    }

    /** A rule read: put in its pair's book if its version is newer than the one held; a move leaves the old book. */
    private Location held(UUID alertId, Location at, PriceAlert alert, long ticket) {
        if (alert.version() <= at.version()) {
            return at.applied(ticket);
        }
        PairKey key = keyOf(alert);
        if (at.key() != null && !at.key().equals(key)) {
            bookOf(at.key()).remove(alertId);
        }
        PriceAlertBook book = bookOf(key);
        book.put(alert);
        OptionalLong version = book.versionOf(alertId);
        return version.isPresent()
                ? new Location(key, version.getAsLong(), ticket, at.reading(), null)
                : marker(alertId, Math.max(at.version(), alert.version()), ticket, at.reading());
    }

    /** No ACTIVE rule read: out of its book, with a marker against older reads still in flight. */
    private Location removed(UUID alertId, Location at, long ticket) {
        if (at.key() != null) {
            bookOf(at.key()).remove(alertId);
        }
        return marker(alertId, at.version(), ticket, at.reading());
    }

    private Location marker(UUID alertId, long version, long applied, int reading) {
        marked.add(alertId);
        return new Location(null, version, applied, reading, clock.instant());
    }

    /** The entry, or none once it guards nothing: in no book, no read in flight, and no marker within the TTL. */
    private Location settled(Location at) {
        return at.key() == null && at.reading() == 0 && !marking(at) ? null : at;
    }

    /** Whether the entry is a marker still within {@code tombstoneTtl}; kept at exactly the TTL, like a tombstone. */
    private boolean marking(Location at) {
        return at.removedAt() != null
                && !at.removedAt().isBefore(clock.instant().minus(properties.tombstoneTtl()));
    }

    /**
     * Forgets the entries of expired markers, oldest first, and stops at the first marker still within the TTL. A
     * marker whose alert still has a read in flight stays; that read settles it when it is applied.
     */
    private void forgetExpiredMarkers() {
        for (int left = marked.size(); left > 0; left--) {
            UUID alertId = marked.peek();
            if (alertId == null) {
                return;
            }
            Location at = located.get(alertId);
            if (at != null && marking(at)) {
                return;
            }
            marked.poll();
            located.computeIfPresent(alertId, (id, current) -> settled(current));
        }
    }

    /**
     * A change the partition makes to one book, applied under the alert's entry like every other change.
     *
     * @param recorded whether the rule leaves after a recorded trigger, so its version stays refused, or after a
     *     refused one, so the read that follows may put the same version back
     */
    private void changeInBook(PairKey key, UUID alertId, boolean recorded, Runnable change) {
        located.compute(alertId, (id, current) -> {
            change.run();
            // Moved by a refresh meanwhile: this book no longer holds it, so the entry stays as it is.
            if (current != null && current.key() != null && !current.key().equals(key)) {
                return current;
            }
            Location at = current == null ? Location.NONE : current;
            OptionalLong version = bookOf(key).versionOf(id);
            if (version.isPresent()) {
                return new Location(key, version.getAsLong(), at.applied(), at.reading(), null);
            }
            if (at.key() == null) {
                return settled(at);
            }
            return settled(
                    recorded
                            ? marker(id, at.version(), at.applied(), at.reading())
                            : new Location(null, -1, at.applied(), at.reading(), null));
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
                    changeInBook(key, alertId, true, () -> book.triggered(hit));
                } else {
                    countRefusal(alertId);
                    // The read below puts it back if the row is still ACTIVE.
                    changeInBook(
                            key,
                            alertId,
                            false,
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

    /**
     * An alert's entry in {@code located}.
     *
     * @param key the book that holds it, or {@code null} when none does
     * @param version the version held or last held; {@code -1} when none, or when a refused trigger lets it back
     * @param applied the ticket of the last read applied
     * @param reading how many reads of it are in flight
     * @param removedAt when a read found it no longer ACTIVE or a recorded trigger took it out; {@code null} otherwise
     */
    private record Location(PairKey key, long version, long applied, int reading, Instant removedAt) {

        static final Location NONE = new Location(null, -1, 0, 0, null);

        Location reading(int count) {
            return new Location(key, version, applied, count, removedAt);
        }

        Location applied(long ticket) {
            return new Location(key, version, ticket, reading, removedAt);
        }
    }

    /** A pair on one market. */
    record PairKey(MarketType market, UUID pairId) {

        PairKey {
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(pairId, "pairId");
        }
    }
}
