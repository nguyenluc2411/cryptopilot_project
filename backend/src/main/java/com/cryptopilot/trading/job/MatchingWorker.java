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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
 * <p>Three commands travel the channels: track an activated entry, untrack a cancelled plan, and a price range. A
 * range is only offered, and dropped when the channel is full; track and untrack wait for room, because losing one
 * would leave the books wrong. A fired entry is filled through {@link MatchingService#fill}; if that call fails, the
 * entry goes back into the books and the next range tries again.
 *
 * <p>The books start from the ACTIVE LIMIT plans when the application is ready. Nothing feeds price ranges yet: the
 * {@code kline_1m} stream of D-43 is connected together with the fill rules (T-043).
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7 and 10; D-09.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer with bounded blocking queues).
 */
@Component
public class MatchingWorker {

    private static final Logger log = LoggerFactory.getLogger(MatchingWorker.class);

    private final MatchingService matching;
    private final MatchingProperties properties;
    private final ThreadFactory threads;
    private final List<BlockingQueue<Command>> partitions = new ArrayList<>();
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
            partitions.add(new ArrayBlockingQueue<>(properties.queueCapacity()));
        }
    }

    /** Loads the ACTIVE LIMIT entries and starts one consumer per partition; a second call does nothing. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.enabled() || running) {
            return;
        }
        running = true;
        List<TrackedEntry> entries = matching.activeEntries();
        entries.forEach(this::track);
        for (int i = 0; i < partitions.size(); i++) {
            BlockingQueue<Command> queue = partitions.get(i);
            Thread consumer = threads.newThread(() -> drain(queue));
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
        partitions.forEach(BlockingQueue::clear);
    }

    /**
     * Queues a price range without waiting.
     *
     * @return whether it was queued
     */
    public boolean submit(PriceRange range) {
        if (!running) {
            return false;
        }
        if (partitionOf(range.market(), range.pairId()).offer(new Command.Range(range))) {
            return true;
        }
        log.warn("NSF-07 {} {} range at {} dropped: its partition is full", range.market(), range.pairId(), range.at());
        return false;
    }

    /** A LIMIT plan just activated waits in the books; a MARKET entry is filled on activation (T-043). */
    @TransactionalEventListener
    public void onActivated(TradingPlanActivated event) {
        if (running && event.entryType() == EntryType.LIMIT) {
            track(new TrackedEntry(
                    event.planId(), event.market(), event.pairId(), event.direction(), event.entryPrice()));
        }
    }

    /** A cancelled plan leaves the books. The event carries no pair, so every partition is told. */
    @TransactionalEventListener
    public void onCancelled(TradingPlanCancelled event) {
        if (running) {
            partitions.forEach(queue -> put(queue, new Command.Untrack(event.planId())));
        }
    }

    private void track(TrackedEntry entry) {
        put(partitionOf(entry.market(), entry.pairId()), new Command.Track(entry));
    }

    private BlockingQueue<Command> partitionOf(MarketType market, UUID pairId) {
        return partitions.get(Math.floorMod(Objects.hash(market, pairId), partitions.size()));
    }

    private static void put(BlockingQueue<Command> queue, Command command) {
        try {
            queue.put(command);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain(BlockingQueue<Command> queue) {
        MatchingEngine engine = new MatchingEngine();
        while (!Thread.currentThread().isInterrupted()) {
            Command command;
            try {
                command = queue.take();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
            switch (command) {
                case Command.Track track -> engine.track(track.entry());
                case Command.Untrack untrack -> engine.untrack(untrack.planId());
                case Command.Range range -> fill(engine, range.range());
            }
        }
    }

    private void fill(MatchingEngine engine, PriceRange range) {
        for (TrackedEntry entry : engine.onRange(range)) {
            try {
                if (!matching.fill(entry.planId(), range.at())) {
                    log.debug("NSF-07 plan {} was no longer ACTIVE", entry.planId());
                }
            } catch (RuntimeException failure) {
                log.error("NSF-07 plan {} fill failed; it stays in the books", entry.planId(), failure);
                engine.track(entry);
            }
        }
    }

    /** What a partition's consumer is asked to do. */
    private sealed interface Command {

        record Track(TrackedEntry entry) implements Command {}

        record Untrack(UUID planId) implements Command {}

        record Range(PriceRange range) implements Command {}
    }
}
