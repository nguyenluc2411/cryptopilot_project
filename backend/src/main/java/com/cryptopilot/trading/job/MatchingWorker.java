package com.cryptopilot.trading.job;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.event.PositionLevelsChanged;
import com.cryptopilot.trading.event.PositionOpened;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.matching.MatchingEngine;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PriceSource;
import com.cryptopilot.trading.service.MatchingService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the matching engine: a fixed number of partitions, each a bounded channel drained by one thread that owns a
 * {@link MatchingEngine}. A pair always lands in the same partition (by market and pair), so its books see the
 * updates in arrival order without locks, while different pairs are matched in parallel.
 *
 * <p>Commands: track an activated plan, untrack a cancelled or expired plan, open a position filled at activation,
 * close a position closed elsewhere, and a price range of last or mark prices. Track, untrack and open wait for room in the channel, because losing one would leave
 * the books wrong. A range never waits and is never lost: when the channel is full it waits as a pending range of its
 * pair (see {@link #submit}). An outcome the engine decided, a fill, an invalidation or an exit, that fails to be
 * stored is kept as it is and tried again on the pair's next range once its back-off has passed, until a deadline. A
 * fill that is stored opens its position in the engine at once, and the range that filled it is applied to the
 * position (an exit in the entry candle, Q-59).
 *
 * <p>Each start builds new partitions from the waiting plans (ACTIVE LIMIT and STOP, with their invalidation levels)
 * and the OPEN positions of the journal (their stop loss, take profit and liquidation price), written straight into
 * each engine before its consumer starts, and never while a consumer of the previous run is still alive.
 *
 * <p>Rule: NSF-07, BR-33, BR-35, BR-68; TECHNICAL_DESIGN 7.7 and 10; D-09, D-77, D-78; D-111 (M1, M3); D-119.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer with bounded blocking queues) and ch. 7 (cancellation and shutdown).
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley, "Message
 * Channel" and "Aggregator".
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 (bounded retries).
 * <p>Reference: Metcalfe, R. M. &amp; Boggs, D. R. (1976). Ethernet: distributed packet switching for local computer
 * networks. <i>Communications of the ACM</i>, 19(7), 395-404 (exponential back-off).
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (a watermark never passes
 * event time whose input is not complete).
 */
@Component
public class MatchingWorker {

    private static final Logger log = LoggerFactory.getLogger(MatchingWorker.class);

    private final MatchingService matching;
    private final MatchingProperties properties;
    private final ThreadFactory threads;
    private final Clock clock;
    private final DoubleSupplier random;

    /** Replaced as a whole by each start; the consumers of a run keep their own partitions. */
    private volatile List<Partition> partitions = List.of();

    /** Consumers started and not yet seen to end; guarded by {@code this}. */
    private final List<Thread> consumers = new ArrayList<>();

    private volatile boolean running;

    @Autowired
    public MatchingWorker(MatchingService matching, MatchingProperties properties, Clock clock) {
        this(matching, properties, clock, Thread.ofVirtual().factory(), () -> ThreadLocalRandom.current()
                .nextDouble());
    }

    MatchingWorker(
            MatchingService matching,
            MatchingProperties properties,
            Clock clock,
            ThreadFactory threads,
            DoubleSupplier random) {
        this.matching = matching;
        this.properties = properties;
        this.clock = clock;
        this.threads = threads;
        this.random = random;
    }

    /**
     * Builds the books from the waiting plans and starts one consumer per partition. A second call does nothing,
     * and nothing starts while a consumer of the previous run has not ended.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.enabled() || running) {
            return;
        }
        if (!consumersEnded()) {
            log.error(
                    "NSF-07 matching not started: {} consumers of the previous run are still running",
                    consumers.size());
            return;
        }
        List<Partition> fresh = new ArrayList<>();
        for (int i = 0; i < properties.partitions(); i++) {
            fresh.add(new Partition(properties.queueCapacity()));
        }
        partitions = List.copyOf(fresh);
        // Set before the rebuild, so an activation or cancel committed during it is queued and applied after it.
        running = true;
        List<TrackedEntry> entries = matching.activeEntries();
        List<TrackedPosition> open = matching.openPositions();
        Map<UUID, List<PositionLevelChange>> levelChanges = matching.openLevelChanges();
        // Straight into the engines, never through the bounded channels: no consumer runs yet to empty them.
        entries.forEach(
                entry -> partitionOf(entry.market(), entry.pairId()).engine.track(entry));
        open.forEach(position -> {
            MatchingEngine engine = partitionOf(position.market(), position.pairId()).engine;
            engine.open(position);
            // The levels a replayed candle older than a change is decided by (D-124).
            levelChanges.getOrDefault(position.positionId(), List.of()).forEach(engine::recordLevelChange);
        });
        for (int i = 0; i < fresh.size(); i++) {
            Thread consumer = threads.newThread(fresh.get(i)::drain);
            consumer.setName("trading-matching-" + i);
            consumer.start();
            consumers.add(consumer);
        }
        log.info(
                "NSF-07 matching started with {} entries and {} open positions in {} partitions",
                entries.size(),
                open.size(),
                fresh.size());
    }

    /** Stops the consumers and waits up to {@code stopTimeout} for them to end; the books are rebuilt at the next start. */
    @PreDestroy
    public synchronized void stop() {
        running = false;
        partitions.forEach(Partition::stop);
        consumers.forEach(Thread::interrupt);
        if (!consumersEnded()) {
            log.warn("NSF-07 {} matching consumers did not end within {}", consumers.size(), properties.stopTimeout());
        }
    }

    /** Whether the engine accepts ranges and commands. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Hands a price range to its partition without waiting. When the channel is full, or the pair has commands or
     * ranges waiting ahead of it, the range waits as a pending range of its pair; the pending ranges of the same
     * candle are merged into one, with the lowest low, the highest high and the latest time (an Aggregator, Hohpe
     * &amp; Woolf 2003). Every price the separate updates reached is still reached, and since they share a candle, the
     * fill time (D-78) is the same. The order rule of {@link Partition} keeps the pair's ranges, activations and
     * cancels in the order they arrived.
     *
     * @return whether the range was accepted; {@code false} only when the engine is not running
     */
    public boolean submit(PriceRange range) {
        if (!running) {
            return false;
        }
        partitionOf(range.market(), range.pairId()).offer(new PairKey(range.market(), range.pairId()), range);
        return true;
    }

    /**
     * A LIMIT plan not filled at its activation, or a STOP plan, waits in the books with its invalidation levels
     * (BR-33, BR-68).
     */
    @TransactionalEventListener
    public void onActivated(TradingPlanActivated event) {
        if (running && event.entryType() != EntryType.MARKET) {
            TrackedEntry entry = new TrackedEntry(
                    event.planId(),
                    event.userId(),
                    event.market(),
                    event.pairId(),
                    event.direction(),
                    event.entryType(),
                    event.entryPrice(),
                    event.stopLoss(),
                    event.takeProfit(),
                    event.invalidationPrice(),
                    event.activatedAt());
            PairKey key = new PairKey(event.market(), event.pairId());
            Partition partition = partitionOf(event.market(), event.pairId());
            partition.put(new Command.Track(key, partition.seal(key), entry));
        }
    }

    /**
     * A plan filled at its activation opens its position in the exit books of its pair's partition (BR-35); a fill the
     * engine decides opens it there directly.
     */
    @TransactionalEventListener
    public void onPositionOpened(PositionOpened event) {
        if (running) {
            TrackedPosition position = new TrackedPosition(
                    event.positionId(),
                    event.planId(),
                    event.userId(),
                    event.market(),
                    event.pairId(),
                    event.direction(),
                    event.entryPrice(),
                    event.entryTime(),
                    event.stopLoss(),
                    event.takeProfit(),
                    event.liquidationPrice(),
                    event.version());
            PairKey key = new PairKey(event.market(), event.pairId());
            Partition partition = partitionOf(event.market(), event.pairId());
            partition.put(new Command.Open(key, partition.seal(key), position));
        }
    }

    /**
     * A change of an open position's levels replaces them in the exit books of its pair's partition, in the order of
     * the pair's ranges, and keeps the earlier levels for the candles that opened before it (D-124).
     */
    @TransactionalEventListener
    public void onLevelsChanged(PositionLevelsChanged event) {
        if (running) {
            TrackedPosition position = event.position();
            PairKey key = new PairKey(position.market(), position.pairId());
            Partition partition = partitionOf(position.market(), position.pairId());
            partition.put(new Command.ChangeLevels(key, partition.seal(key), position, event.change()));
        }
    }

    /** A cancelled plan leaves the books of its pair's partition. */
    @TransactionalEventListener
    public void onCancelled(TradingPlanCancelled event) {
        untrack(event.market(), event.pairId(), event.planId());
    }

    /** An expired plan leaves the books of its pair's partition (NSF-09). */
    @TransactionalEventListener
    public void onExpired(TradingPlanExpired event) {
        untrack(event.market(), event.pairId(), event.planId());
    }

    /**
     * A closed position leaves the exit and mark books of its pair's partition: one the Trader closed (UC-21) would
     * otherwise wait there until a range reached its levels. An exit the engine decided has left them already, and a
     * second removal does nothing.
     */
    @TransactionalEventListener
    public void onPositionClosed(PositionClosed event) {
        if (running) {
            PairKey key = new PairKey(event.market(), event.pairId());
            Partition partition = partitionOf(event.market(), event.pairId());
            partition.put(new Command.Close(key, partition.seal(key), event.positionId()));
        }
    }

    private void untrack(MarketType market, UUID pairId, UUID planId) {
        if (running) {
            PairKey key = new PairKey(market, pairId);
            Partition partition = partitionOf(market, pairId);
            partition.put(new Command.Untrack(key, partition.seal(key), planId));
        }
    }

    /**
     * The wait before the next try after {@code failures} failures: the initial delay doubled per failure up to the
     * maximum, less a random share of up to {@code jitterPercent} so failing fills do not retry in step.
     *
     * <p>Reference: Metcalfe &amp; Boggs (1976), exponential back-off.
     *
     * @param random a value in [0, 1)
     */
    static Duration backoff(MatchingProperties.Retry retry, int failures, double random) {
        double doubled = retry.initialDelay().toMillis() * Math.pow(2, Math.min(failures - 1, 30));
        long capped = (long) Math.min(doubled, retry.maxDelay().toMillis());
        long jitter = (long) (capped * retry.jitterPercent() / 100.0 * random);
        return Duration.ofMillis(capped - jitter);
    }

    /** Two updates of the same candle as one: the lowest low, the highest high, the earliest and latest time. */
    static PriceRange merge(PriceRange earlier, PriceRange later) {
        return earlier.mergedWith(later);
    }

    /** Joins the consumers within {@code stopTimeout} in all; forgets those that ended. Guarded by {@code this}. */
    private boolean consumersEnded() {
        long deadline = System.nanoTime() + properties.stopTimeout().toNanos();
        for (Iterator<Thread> it = consumers.iterator(); it.hasNext(); ) {
            Thread consumer = it.next();
            long left = deadline - System.nanoTime();
            try {
                if (left > 0) {
                    consumer.join(Duration.ofNanos(left));
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!consumer.isAlive()) {
                it.remove();
            }
        }
        return consumers.isEmpty();
    }

    private Partition partitionOf(MarketType market, UUID pairId) {
        List<Partition> current = partitions;
        return current.get(Math.floorMod(Objects.hash(market, pairId), current.size()));
    }

    /**
     * One partition: its channel, the pending ranges of each pair, and the state its consumer owns.
     *
     * <p>Order rule, per pair: every pending range, activation and cancel takes a number from the partition when it
     * arrives, and they are applied in that order. An activation or cancel closes the pair's pending range, so later
     * ranges never merge across it, and before it is applied the pending ranges numbered below it are matched. While
     * one waits for room in the channel, the pair's new ranges wait as pending ranges too. So a range matches a plan
     * only if it arrived after the plan's activation, and a range that reached an entry before its cancel still
     * fills it.
     *
     * <p>A pair's pending ranges are matched as soon as none of its ranges is left in the channel ahead of them, after
     * whatever command the consumer has just handled; they never wait for the other pairs' traffic to stop.
     *
     * <p>Reference: Lamport, L. (1978). Time, clocks, and the ordering of events in a distributed system.
     * <i>Communications of the ACM</i>, 21(7), 558-565.
     */
    private final class Partition {

        private final BlockingQueue<Command> queue;

        /** Guards {@link #pairs} and {@link #sequence}; never held while waiting. */
        private final Object lock = new Object();

        private final Map<PairKey, PairState> pairs = new HashMap<>();
        private long sequence;

        /** Written by the starting thread before the consumer starts, then by the consumer only. */
        private final MatchingEngine engine = new MatchingEngine();

        /**
         * Outcomes that failed to be stored, per pair, as the engine decided them, with their failures and next try: a
         * retry list, tried on the pair's next range once due, so an outcome is not lost when the price moves away
         * (Hohpe &amp; Woolf 2003). A retry runs only when the pair's next price update arrives (about every 2 s on
         * {@code kline_1m}); no timer fires it.
         */
        private final Map<PairKey, List<PendingOutcome>> retries = new HashMap<>();

        /**
         * Pairs with an outcome given up since the start: their watermark stays before the candle that decided it, so
         * a restart replays that candle (Akidau et al. 2015: a watermark must not pass input not fully processed).
         */
        private final Set<PairKey> watermarkHeld = new HashSet<>();

        Partition(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        /** Set by {@link #stop()}: the consumer ends after its current command, even if that command ate the interrupt. */
        private volatile boolean stopped;

        void stop() {
            stopped = true;
            queue.clear();
            synchronized (lock) {
                pairs.clear();
            }
            // Wakes a consumer waiting on an empty channel whose interrupt was lost.
            queue.offer(new Command.Stop());
        }

        /** Queues the range, or keeps it pending when the channel is full or something of its pair is ahead of it. */
        void offer(PairKey key, PriceRange range) {
            synchronized (lock) {
                PairState state = pairs.get(key);
                boolean behindNothing = state == null || (state.segments.isEmpty() && state.notQueued.isEmpty());
                if (behindNothing && queue.offer(new Command.Range(key, range))) {
                    pairs.computeIfAbsent(key, ignored -> new PairState()).queued++;
                    return;
                }
                state = pairs.computeIfAbsent(key, ignored -> new PairState());
                Segment same = openSegmentOf(state, range);
                if (same != null) {
                    same.range = merge(same.range, range);
                } else {
                    state.segments.add(new Segment(++sequence, range));
                    warnOnBacklog(key, state);
                }
                // Wakes an idle consumer; at most one per pair. When the channel is full, the consumer is busy and
                // reads the pending ranges after its next command anyway.
                if (!state.flushQueued && queue.offer(new Command.Flush(key))) {
                    state.flushQueued = true;
                }
            }
        }

        /**
         * The open pending range of the same candle and source as {@code range}, looking back over the open ranges of
         * that candle: last and mark prices of one minute alternate, and each merges with its own kind, so a backlog
         * holds at most one range of each per minute.
         */
        private static Segment openSegmentOf(PairState state, PriceRange range) {
            for (Iterator<Segment> it = state.segments.descendingIterator(); it.hasNext(); ) {
                Segment segment = it.next();
                if (!segment.open || !segment.range.from().equals(range.from())) {
                    return null;
                }
                if (segment.range.source() == range.source()) {
                    return segment;
                }
            }
            return null;
        }

        /** Warns once per backlog when a pair holds more pending ranges than the threshold. */
        private void warnOnBacklog(PairKey key, PairState state) {
            if (!state.backlogWarned && state.segments.size() > properties.pendingWarnThreshold()) {
                state.backlogWarned = true;
                log.warn(
                        "NSF-07 {} {} holds {} pending ranges; matching is behind",
                        key.market(),
                        key.pairId(),
                        state.segments.size());
            }
        }

        /** Numbers an activation or cancel of the pair and closes its pending range. */
        long seal(PairKey key) {
            synchronized (lock) {
                PairState state = pairs.computeIfAbsent(key, ignored -> new PairState());
                Segment last = state.segments.peekLast();
                if (last != null) {
                    last.open = false;
                }
                long number = ++sequence;
                state.unhandled.add(number);
                state.notQueued.add(number);
                return number;
            }
        }

        void put(PairCommand command) {
            try {
                queue.put(command);
                // In the channel now, so the pair's next ranges can follow it there.
                synchronized (lock) {
                    // The consumer may have handled it already and dropped the pair's state.
                    Optional.ofNullable(pairs.get(command.key()))
                            .ifPresent(state -> state.notQueued.remove(command.number()));
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                log.warn("NSF-07 {} lost: interrupted while waiting for room in its partition", command);
                synchronized (lock) {
                    forget(command);
                }
            }
        }

        void drain() {
            // Ends on the stop flag, or through take(): an interrupt set while a command runs makes it throw.
            while (!stopped) {
                Command command;
                try {
                    command = queue.take();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    handle(command);
                    dueRanges().forEach(this::onRange);
                } catch (Exception unexpected) {
                    // Anything not caught below; the consumer must outlive one bad command.
                    log.error("NSF-07 {} failed; the partition carries on", command, unexpected);
                }
            }
        }

        private void handle(Command command) {
            switch (command) {
                case Command.Track track -> {
                    dueRanges(track).forEach(this::onRange);
                    engine.track(track.entry());
                }
                case Command.Untrack untrack -> {
                    dueRanges(untrack).forEach(this::onRange);
                    engine.untrack(untrack.planId());
                    retries.values()
                            .forEach(waiting -> waiting.removeIf(p -> !(p.outcome() instanceof PositionExit)
                                    && p.outcome().planId().equals(untrack.planId())));
                }
                case Command.Open open -> {
                    dueRanges(open).forEach(this::onRange);
                    engine.open(open.position());
                }
                case Command.Close close -> {
                    dueRanges(close).forEach(this::onRange);
                    engine.close(close.positionId());
                    retries.values()
                            .forEach(waiting -> waiting.removeIf(p -> p.outcome() instanceof PositionExit exit
                                    && exit.position().positionId().equals(close.positionId())));
                }
                case Command.ChangeLevels change -> {
                    dueRanges(change).forEach(this::onRange);
                    engine.changeLevels(change.position(), change.change());
                }
                case Command.Range range -> {
                    onRange(range.range());
                    update(range.key(), state -> state.queued--);
                }
                case Command.Flush flush -> update(flush.key(), state -> state.flushQueued = false);
                case Command.Stop stop -> {
                    // The loop ends on the flag.
                }
            }
        }

        /** Changes the pair's state, which a stop may have cleared, and drops it once nothing is left in it. */
        private void update(PairKey key, Consumer<PairState> change) {
            synchronized (lock) {
                PairState state = pairs.get(key);
                if (state != null) {
                    change.accept(state);
                    if (state.isIdle()) {
                        pairs.remove(key);
                    }
                }
            }
        }

        /**
         * The command's pair's pending ranges numbered below its oldest unhandled command, which may be this one: the
         * ranges that arrived before it. The command then counts as handled.
         */
        private List<PriceRange> dueRanges(PairCommand command) {
            List<PriceRange> due = new ArrayList<>();
            synchronized (lock) {
                Optional.ofNullable(pairs.get(command.key())).ifPresent(state -> takeDue(state, due));
                forget(command);
            }
            return due;
        }

        /** The pending ranges of every pair with none of its ranges left in the channel, up to its oldest command. */
        private List<PriceRange> dueRanges() {
            List<PriceRange> due = new ArrayList<>();
            synchronized (lock) {
                for (Iterator<PairState> it = pairs.values().iterator(); it.hasNext(); ) {
                    PairState state = it.next();
                    if (state.queued == 0) {
                        takeDue(state, due);
                    }
                    if (state.isIdle()) {
                        it.remove();
                    }
                }
            }
            return due;
        }

        private static void takeDue(PairState state, List<PriceRange> due) {
            long limit = state.unhandled.isEmpty() ? Long.MAX_VALUE : state.unhandled.first();
            while (!state.segments.isEmpty() && state.segments.peekFirst().number < limit) {
                due.add(state.segments.pollFirst().range);
            }
        }

        /** The command is handled or lost; drops the pair's state once nothing is left in it. */
        private void forget(PairCommand command) {
            PairState state = pairs.get(command.key());
            if (state == null) {
                return;
            }
            state.unhandled.remove(command.number());
            state.notQueued.remove(command.number());
            if (state.isIdle()) {
                pairs.remove(command.key());
            }
        }

        private void onRange(PriceRange range) {
            PairKey key = new PairKey(range.market(), range.pairId());
            retryDue(key);
            engine.onRange(range).forEach(outcome -> store(key, outcome, null, range));
            // The watermark follows the last price candles; the mark price candles of a replay come with them.
            if (range.closed()
                    && range.source() == PriceSource.LAST
                    && !retries.containsKey(key)
                    && !watermarkHeld.contains(key)) {
                advanceWatermark(range);
            }
        }

        /** Tries again the pair's failed outcomes whose back-off has passed; the others keep waiting. */
        private void retryDue(PairKey key) {
            List<PendingOutcome> waiting = retries.get(key);
            if (waiting == null) {
                return;
            }
            Instant now = clock.instant();
            List<PendingOutcome> due = new ArrayList<>();
            waiting.removeIf(retry -> {
                boolean isDue = !retry.nextTryAt().isAfter(now);
                if (isDue) {
                    due.add(retry);
                }
                return isDue;
            });
            if (waiting.isEmpty()) {
                retries.remove(key);
            }
            due.forEach(retry -> store(key, retry.outcome(), retry, null));
        }

        /**
         * The candle is closed and every outcome it decided is stored: a restart replays from the next candle. Not
         * advanced while an outcome of the pair waits for a retry or was given up, so a restart replays the candle
         * that decided it.
         */
        private void advanceWatermark(PriceRange range) {
            try {
                matching.advanceWatermark(range.market(), range.pairId(), range.from());
            } catch (RuntimeException failure) {
                log.warn(
                        "NSF-07 {} {} watermark not advanced to {}: {}",
                        range.market(),
                        range.pairId(),
                        range.from(),
                        failure.toString());
            }
        }

        /**
         * Stores an outcome: a fill or an invalidation, each a compare-and-set on the plan's status, or an exit, a
         * compare-and-set on the journal row's. A stored fill opens its position here, and the range that filled it,
         * when known, is applied to the position at once. A failure of any kind keeps the outcome in the retry list
         * with an exponential back-off; once the deadline since its first failure has passed it is logged once as an
         * error and dropped, and the plan stays ACTIVE, or the position OPEN, in the database for a person to handle.
         *
         * @param previous the retry this try comes from, or {@code null} for a first try
         * @param range the range that decided the outcome, or {@code null} for a retry
         */
        private void store(PairKey key, PlanOutcome outcome, PendingOutcome previous, PriceRange range) {
            try {
                boolean stored =
                        switch (outcome) {
                            case Fill fill -> {
                                Optional<TrackedPosition> opened = matching.fill(fill);
                                opened.ifPresent(position -> {
                                    if (range == null) {
                                        engine.open(position);
                                    } else {
                                        engine.openAfterFill(position, range)
                                                .forEach(exit -> store(key, exit, null, range));
                                    }
                                });
                                yield opened.isPresent();
                            }
                            case Invalidation invalidation -> matching.invalidate(invalidation);
                            case PositionExit exit -> matching.close(exit);
                        };
                if (!stored) {
                    log.debug("NSF-07 {} of plan {} changed nothing: decided first", kind(outcome), outcome.planId());
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    // A stop: keep the signal for the consumer loop.
                    Thread.currentThread().interrupt();
                }
                Instant now = clock.instant();
                int failures = previous == null ? 1 : previous.failures() + 1;
                Instant firstFailedAt = previous == null ? now : previous.firstFailedAt();
                if (!now.isBefore(firstFailedAt.plus(properties.retry().deadline()))) {
                    watermarkHeld.add(key);
                    log.error(
                            "NSF-07 plan {} {} failed {} times since {}; no longer retried, the {} and the pair's"
                                    + " watermark is held. A restart is required within replay.max-window ({}) to"
                                    + " replay the candle that decided it",
                            outcome.planId(),
                            kind(outcome),
                            failures,
                            firstFailedAt,
                            outcome instanceof PositionExit ? "position stays OPEN" : "plan stays ACTIVE",
                            properties.replay().maxWindow(),
                            failure);
                    return;
                }
                Duration wait = backoff(properties.retry(), failures, random.getAsDouble());
                log.warn(
                        "NSF-07 plan {} {} failed ({}); retried in {}: {}",
                        outcome.planId(),
                        kind(outcome),
                        failures,
                        wait,
                        failure.toString());
                retries.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new PendingOutcome(outcome, failures, firstFailedAt, now.plus(wait)));
            }
        }

        private static String kind(PlanOutcome outcome) {
            return switch (outcome) {
                case Fill fill -> "fill";
                case Invalidation invalidation -> "invalidation";
                case PositionExit exit -> "exit";
            };
        }
    }

    /**
     * The pending ranges of a pair, its ranges in the channel, and the numbers of its commands not handled yet;
     * {@code notQueued} are those still waiting for room in the channel, which the pair's new ranges must not overtake.
     */
    private static final class PairState {

        private final Deque<Segment> segments = new ArrayDeque<>();
        private final TreeSet<Long> unhandled = new TreeSet<>();
        private final Set<Long> notQueued = new HashSet<>();
        private int queued;
        private boolean flushQueued;
        private boolean backlogWarned;

        boolean isIdle() {
            return segments.isEmpty() && unhandled.isEmpty() && queued == 0 && !flushQueued;
        }
    }

    /** Updates of one candle merged between two commands of a pair; numbered when it opens, closed by a command. */
    private static final class Segment {

        private final long number;
        private PriceRange range;
        private boolean open = true;

        Segment(long number, PriceRange range) {
            this.number = number;
            this.range = range;
        }
    }

    private record PairKey(MarketType market, UUID pairId) {}

    /** An outcome to store again: its failures so far, when the first one happened, and when to try next. */
    private record PendingOutcome(PlanOutcome outcome, int failures, Instant firstFailedAt, Instant nextTryAt) {}

    /** What a partition's consumer is asked to do. */
    private sealed interface Command {

        record Range(PairKey key, PriceRange range) implements Command {}

        /** Read the pending ranges; at most one per pair is in the channel. */
        record Flush(PairKey key) implements Command {}

        /** End the consumer. */
        record Stop() implements Command {}

        record Track(PairKey key, long number, TrackedEntry entry) implements PairCommand {}

        record Untrack(PairKey key, long number, UUID planId) implements PairCommand {}

        record Open(PairKey key, long number, TrackedPosition position) implements PairCommand {}

        record Close(PairKey key, long number, UUID positionId) implements PairCommand {}

        record ChangeLevels(PairKey key, long number, TrackedPosition position, PositionLevelChange change)
                implements PairCommand {}
    }

    /**
     * An activation, a cancel or expiry, a position opened at activation or closed, or a change of its levels, of
     * one pair, with its number.
     */
    private sealed interface PairCommand extends Command {

        PairKey key();

        long number();
    }
}
