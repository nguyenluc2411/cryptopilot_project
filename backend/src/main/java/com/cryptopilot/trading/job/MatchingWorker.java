package com.cryptopilot.trading.job;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.matching.MatchingEngine;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.service.MatchingService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
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
 * <p>Commands: track an activated entry, untrack a cancelled plan, and a price range. Track and untrack wait for room
 * in the channel, because losing one would leave the books wrong. A range never waits and is never lost: when the
 * channel is full it is merged into the pending range of its pair (see {@link #submit}). A fill that fails is kept
 * with its original time and tried again on the pair's next range.
 *
 * <p>The books start from the ACTIVE LIMIT plans, written straight into each engine before its consumer starts.
 * Nothing feeds price ranges yet: the {@code kline_1m} stream of D-43 is connected with the fill rules (T-043, Q-32).
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7 and 10; D-09.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer with bounded blocking queues).
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley, "Message
 * Channel" and "Aggregator".
 */
@Component
public class MatchingWorker {

    private static final Logger log = LoggerFactory.getLogger(MatchingWorker.class);

    private final MatchingService matching;
    private final MatchingProperties properties;
    private final ThreadFactory threads;
    private final List<Partition> partitions = new ArrayList<>();
    private final List<Thread> consumers = new ArrayList<>();
    private volatile boolean running;

    @Autowired
    public MatchingWorker(MatchingService matching, MatchingProperties properties) {
        this(matching, properties, Thread.ofVirtual().factory());
    }

    MatchingWorker(MatchingService matching, MatchingProperties properties, ThreadFactory threads) {
        this.matching = matching;
        this.properties = properties;
        this.threads = threads;
        for (int i = 0; i < properties.partitions(); i++) {
            partitions.add(new Partition(properties.queueCapacity()));
        }
    }

    /** Rebuilds the books from the ACTIVE LIMIT plans and starts one consumer per partition; a second call does nothing. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.enabled() || running) {
            return;
        }
        // Set first, so an activation or cancel committed during the rebuild is queued and applied after it.
        running = true;
        partitions.forEach(Partition::reset);
        List<TrackedEntry> entries = matching.activeEntries();
        // Straight into the engines, never through the bounded channels: no consumer runs yet to empty them.
        entries.forEach(
                entry -> partitionOf(entry.market(), entry.pairId()).engine.track(entry));
        // Ranges from before this point are not seen; the restart replay of T-043 covers them.
        for (int i = 0; i < partitions.size(); i++) {
            Thread consumer = threads.newThread(partitions.get(i)::drain);
            consumer.setName("trading-matching-" + i);
            consumer.start();
            consumers.add(consumer);
        }
        log.info("NSF-07 matching started with {} entries in {} partitions", entries.size(), partitions.size());
    }

    /** Stops the consumers; the books are rebuilt at the next start. */
    @PreDestroy
    public synchronized void stop() {
        running = false;
        consumers.forEach(Thread::interrupt);
        consumers.clear();
        partitions.forEach(partition -> {
            partition.queue.clear();
            partition.pending.clear();
        });
    }

    /**
     * Hands a price range to its partition without waiting. When the channel is full, the range is merged into the
     * pair's pending range: the lowest low, the highest high and the latest time. Pending ranges are matched once the
     * channel is empty. Every price the separate ranges
     * reached is still reached by the merged one, so no candle's extreme is lost (an Aggregator, Hohpe &amp; Woolf
     * 2003).
     *
     * @return whether the range was accepted; {@code false} only when the engine is not running
     */
    public boolean submit(PriceRange range) {
        if (!running) {
            return false;
        }
        Partition partition = partitionOf(range.market(), range.pairId());
        PairKey key = new PairKey(range.market(), range.pairId());
        // Once a pair has a pending range, later ones join it, so the pair's ranges stay in order.
        if (partition.pending.containsKey(key) || !partition.queue.offer(new Command.Range(range))) {
            partition.pending.merge(key, range, MatchingWorker::merge);
            // Wakes a consumer that has emptied the channel meanwhile; if the channel is still full, the consumer
            // reaches the pending ranges once it has emptied it.
            partition.queue.offer(new Command.Flush());
        }
        return true;
    }

    /** A LIMIT plan just activated waits in the books; a MARKET entry is filled on activation (T-043). */
    @TransactionalEventListener
    public void onActivated(TradingPlanActivated event) {
        if (running && event.entryType() == EntryType.LIMIT) {
            TrackedEntry entry = new TrackedEntry(
                    event.planId(), event.market(), event.pairId(), event.direction(), event.entryPrice());
            put(partitionOf(event.market(), event.pairId()), new Command.Track(entry));
        }
    }

    /** A cancelled plan leaves the books of its pair's partition. */
    @TransactionalEventListener
    public void onCancelled(TradingPlanCancelled event) {
        if (running) {
            put(partitionOf(event.market(), event.pairId()), new Command.Untrack(event.planId()));
        }
    }

    static PriceRange merge(PriceRange earlier, PriceRange later) {
        return new PriceRange(
                earlier.market(),
                earlier.pairId(),
                earlier.low().min(later.low()),
                earlier.high().max(later.high()),
                earlier.at().isAfter(later.at()) ? earlier.at() : later.at());
    }

    private Partition partitionOf(MarketType market, UUID pairId) {
        return partitions.get(Math.floorMod(Objects.hash(market, pairId), partitions.size()));
    }

    private static void put(Partition partition, Command command) {
        try {
            partition.queue.put(command);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            log.warn("NSF-07 {} lost: interrupted while waiting for room in its partition", command);
        }
    }

    /** One partition: its channel, the ranges merged while the channel was full, and the state its consumer owns. */
    private final class Partition {

        private final BlockingQueue<Command> queue;
        private final Map<PairKey, PriceRange> pending = new ConcurrentHashMap<>();

        /** Read and written by the consumer only, after {@link #reset()} on the starting thread. */
        private MatchingEngine engine = new MatchingEngine();

        /**
         * Fills that failed, per pair, with the time of the range that reached them: a retry list, tried on the
         * pair's next range so a fill is not lost when the price moves away (Hohpe &amp; Woolf 2003).
         */
        private final Map<PairKey, List<PendingFill>> retries = new HashMap<>();

        Partition(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        void reset() {
            engine = new MatchingEngine();
            retries.clear();
        }

        void drain() {
            // Ends only through take(): an interrupt set while a command runs makes the next take() throw.
            while (true) {
                Command command;
                try {
                    command = queue.take();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    handle(command);
                    if (queue.isEmpty()) {
                        handlePending();
                    }
                } catch (Exception unexpected) {
                    // Anything not caught below; the consumer must outlive one bad command.
                    log.error("NSF-07 {} failed; the partition carries on", command, unexpected);
                }
            }
        }

        private void handle(Command command) {
            switch (command) {
                case Command.Track track -> engine.track(track.entry());
                case Command.Untrack untrack -> {
                    engine.untrack(untrack.planId());
                    retries.values()
                            .forEach(waiting ->
                                    waiting.removeIf(p -> p.entry().planId().equals(untrack.planId())));
                }
                case Command.Range range -> onRange(range.range());
                case Command.Flush flush -> {
                    // The pending ranges are read next.
                }
            }
        }

        private void handlePending() {
            for (PairKey key : List.copyOf(pending.keySet())) {
                Optional.ofNullable(pending.remove(key)).ifPresent(this::onRange);
            }
        }

        private void onRange(PriceRange range) {
            PairKey key = new PairKey(range.market(), range.pairId());
            List<PendingFill> waiting = retries.remove(key);
            if (waiting != null) {
                waiting.forEach(retry -> fill(key, retry.entry(), retry.at()));
            }
            engine.onRange(range).forEach(entry -> fill(key, entry, range.at()));
        }

        private void fill(PairKey key, TrackedEntry entry, Instant at) {
            try {
                if (!matching.fill(entry.planId(), at)) {
                    log.debug("NSF-07 plan {} was no longer ACTIVE", entry.planId());
                }
            } catch (RuntimeException failure) {
                log.error("NSF-07 plan {} fill failed; retried on the next range of its pair", entry.planId(), failure);
                retries.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new PendingFill(entry, at));
            }
        }
    }

    private record PairKey(MarketType market, UUID pairId) {}

    /** A fill to try again, at the time the price first reached the entry. */
    private record PendingFill(TrackedEntry entry, Instant at) {}

    /** What a partition's consumer is asked to do. */
    private sealed interface Command {

        record Track(TrackedEntry entry) implements Command {}

        record Untrack(UUID planId) implements Command {}

        record Range(PriceRange range) implements Command {}

        /** Read the pending ranges. */
        record Flush() implements Command {}
    }
}
