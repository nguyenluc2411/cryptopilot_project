package com.cryptopilot.paper.job;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.matching.PaperOrderBook;
import com.cryptopilot.paper.matching.PaperOrderBook.BookKey;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.service.PaperMatchingService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the paper matching engine: one consumer thread owns a {@link PaperOrderBook} and applies to it, in arrival
 * order, the orders that start or stop waiting and the 1-minute candles of the market streams; a replay thread fetches
 * the closed candles the engine missed (Q-T6). Kept apart from the plan engine of the {@code trading} module (Q-T1).
 *
 * <h2>Feeding</h2>
 *
 * <ul>
 *   <li>A closed candle is queued as it comes. The forming updates of a pair are merged into one waiting range per
 *       pair, with the lowest low and highest high of its candle, and queued once: the stream reader never blocks, and
 *       a slow consumer reads the pair's latest prices rather than a backlog of them. A forming update dropped for a
 *       newer candle is not lost, as the closed update of its candle carries it in full.
 *   <li>A range fills every order of the pair it reached, at the order's price, at the open time of its candle
 *       (D-78), under the Trader's lock, in price-time priority; the fills of different Traders are stored at once
 *       ({@code fill-parallelism}), those of one Trader in order; an order cancelled meanwhile is not filled (see
 *       {@link PaperMatchingService#fill}). Ordering between an order and a range does not matter for correctness: a
 *       candle that opened before an order was placed never fills it (D-77).
 * </ul>
 *
 * <h2>Watermark and replay</h2>
 *
 * <ul>
 *   <li>Once a closed candle of a pair with waiting orders is matched and every fill it decided is stored, the pair's
 *       watermark moves to it. It is held while a fill of the pair waits for a retry, was given up, or a replay of the
 *       pair runs, so a restart replays from before what is not settled.
 *   <li>At start, every pair with waiting orders is replayed from the later of the candle after its watermark and the
 *       minute its oldest order was placed, at most {@code replay.max-window} back. While running, a pair with orders
 *       whose closed candles jump past the minute after the last one is replayed from that minute.
 *   <li>A replayed candle fills as a live one would; an order the live updates filled first is found FILLED and
 *       skipped, so a candle applied twice fills nothing twice. Several instances may replay the same pair for the
 *       same reason.
 * </ul>
 *
 * <p>A fill that fails is tried again on the pair's next range once {@code retry-delay} has passed, until
 * {@code retry-deadline}; then the order waits in the book again and the pair's watermark stays held until a restart.
 *
 * <p>Rule: TR-02; NSF-07; Q-T1, Q-T6; A-04; D-77, D-78; ADR-011.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer) and ch. 7 (cancellation and shutdown).
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (a watermark never passes
 * event time whose input is not complete).
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley,
 * "Aggregator" (the forming updates of a candle merged into one).
 */
@Component
public class PaperMatchingWorker implements MinuteKlineListener {

    private static final Logger log = LoggerFactory.getLogger(PaperMatchingWorker.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final PaperMatchingService matching;
    private final MarketApi market;
    private final PaperMatchingProperties properties;
    private final Clock clock;
    private final ThreadFactory threads;

    private final BlockingQueue<Command> queue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Replay> replays = new LinkedBlockingQueue<>();

    /** The merged forming range of each pair, waiting for the consumer; its key is queued once while present. */
    private final Map<BookKey, PriceRange> forming = new ConcurrentHashMap<>();

    /** Owned by the consumer thread once it starts; written by {@link #start()} before that. */
    private final PaperOrderBook book = new PaperOrderBook();

    private final Map<BookKey, Instant> lastClosed = new HashMap<>();
    private final Set<BookKey> replaying = new HashSet<>();
    private final Set<BookKey> watermarkHeld = new HashSet<>();
    private final Map<BookKey, List<PendingFill>> retries = new HashMap<>();

    private volatile boolean running;
    private final AtomicBoolean started = new AtomicBoolean();

    /** Guarded by {@code this}. */
    private final List<Thread> workers = new ArrayList<>();

    @Autowired
    public PaperMatchingWorker(
            PaperMatchingService matching, MarketApi market, PaperMatchingProperties properties, Clock clock) {
        this(matching, market, properties, clock, Thread.ofVirtual().factory());
    }

    PaperMatchingWorker(
            PaperMatchingService matching,
            MarketApi market,
            PaperMatchingProperties properties,
            Clock clock,
            ThreadFactory threads) {
        this.matching = matching;
        this.market = market;
        this.properties = properties;
        this.clock = clock;
        this.threads = threads;
    }

    /**
     * Builds the book from the working LIMIT orders, queues the replays of their pairs and starts the consumer and the
     * replay thread. Runs once; a second call does nothing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.enabled() || !started.compareAndSet(false, true)) {
            return;
        }
        List<RestingOrder> resting = matching.restingOrders();
        Map<BookKey, Instant> oldest = new HashMap<>();
        for (RestingOrder order : resting) {
            book.track(order);
            oldest.merge(keyOf(order), order.placedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }
        if (properties.replay().enabled()) {
            oldest.forEach((key, placedAt) -> startupReplay(key, placedAt));
        }
        running = true;
        startThread("paper-matching", this::drain);
        startThread("paper-replay", this::runReplays);
        log.info("TR-02 paper matching started with {} orders; {} pairs to replay", resting.size(), replaying.size());
    }

    /** Stops the threads and waits up to {@code stop-timeout} for them to end. */
    @PreDestroy
    public synchronized void stop() {
        running = false;
        queue.offer(new Command.Stop());
        workers.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + properties.stopTimeout().toNanos();
        for (Thread worker : workers) {
            try {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    worker.join(Duration.ofNanos(left));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
    }

    /** Whether the engine accepts candles and orders. */
    public boolean isRunning() {
        return running;
    }

    /** Whether nothing is queued for the consumer or the replay thread; for tests. */
    boolean isIdle() {
        return queue.isEmpty() && forming.isEmpty() && replays.isEmpty();
    }

    @Override
    public void onMinuteKline(MinuteKline kline) {
        if (!running) {
            return;
        }
        PriceRange range = new PriceRange(
                kline.market(),
                kline.pairId(),
                kline.low(),
                kline.high(),
                kline.openTime(),
                kline.closed(),
                FillSource.LIVE);
        submit(range);
    }

    /** Hands a live range to the consumer without waiting; see the class comment. */
    void submit(PriceRange range) {
        BookKey key = new BookKey(range.market(), range.pairId());
        if (range.closed()) {
            queue.offer(new Command.Range(range));
            return;
        }
        AtomicBoolean fresh = new AtomicBoolean();
        forming.compute(key, (ignored, waiting) -> {
            fresh.set(waiting == null);
            return waiting == null ? range : waiting.mergedWith(range);
        });
        if (fresh.get()) {
            queue.offer(new Command.Forming(key));
        }
    }

    /** A LIMIT order that did not execute on arrival waits in the book. */
    @TransactionalEventListener
    public void onRested(PaperOrderRested event) {
        if (running) {
            queue.offer(new Command.Track(event.order()));
        }
    }

    /** A cancelled order leaves the book. */
    @TransactionalEventListener
    public void onCanceled(PaperOrderCanceled event) {
        if (running) {
            queue.offer(new Command.Untrack(event.orderId()));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Consumer
    // ------------------------------------------------------------------------------------------

    private void drain() {
        while (running) {
            Command command;
            try {
                command = queue.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                handle(command);
            } catch (RuntimeException unexpected) {
                // The consumer must outlive one bad command.
                log.error("TR-02 paper matching {} failed; the engine carries on", command, unexpected);
            }
        }
    }

    private void handle(Command command) {
        switch (command) {
            case Command.Track track -> book.track(track.order());
            case Command.Untrack untrack -> {
                book.untrack(untrack.orderId());
                retries.values()
                        .forEach(waiting -> waiting.removeIf(
                                pending -> pending.order().orderId().equals(untrack.orderId())));
                // A pair with no fill left to retry no longer holds its watermark.
                retries.values().removeIf(List::isEmpty);
            }
            case Command.Range range -> onRange(range.range());
            case Command.Forming formingKey -> {
                PriceRange range = forming.remove(formingKey.key());
                if (range != null) {
                    onRange(range);
                }
            }
            case Command.ReplayDone done -> {
                replaying.remove(done.key());
                lastClosed.merge(done.key(), done.until().minus(MINUTE), (a, b) -> a.isAfter(b) ? a : b);
            }
            case Command.Stop stop -> {
                // The loop ends on the flag.
            }
        }
    }

    private void onRange(PriceRange range) {
        BookKey key = new BookKey(range.market(), range.pairId());
        if (range.source() == FillSource.LIVE) {
            watchForGap(key, range);
        }
        retryDue(key);
        boolean tracked = book.hasOrders(range.market(), range.pairId());
        List<RestingOrder> reached = book.onRange(range);
        fillAll(
                key,
                reached.stream()
                        .map(order -> new Fill(order, range.from(), range.source(), null))
                        .toList());
        boolean replayed = range.source() == FillSource.REPLAY;
        if (range.closed()
                && (tracked || replayed)
                && (replayed || !replaying.contains(key))
                && !retries.containsKey(key)
                && !watermarkHeld.contains(key)) {
            advanceWatermark(range);
        }
    }

    /**
     * Keeps the pair's last closed live candle, and queues a replay when a pair with orders skips one: a stream drop,
     * a reconnection, a closed candle that never came.
     */
    private void watchForGap(BookKey key, PriceRange range) {
        Instant last = lastClosed.get(key);
        if (last != null
                && range.from().isAfter(last.plus(MINUTE))
                && properties.replay().enabled()
                && book.hasOrders(key.market(), key.pairId())
                && replaying.add(key)) {
            log.warn(
                    "TR-02 {} {}: candles from {} to {} missing from the stream; replaying them",
                    key.market(),
                    key.pairId(),
                    last.plus(MINUTE),
                    range.from());
            replays.offer(new Replay(key, last.plus(MINUTE)));
        }
        if (range.closed() && (last == null || range.from().isAfter(last))) {
            lastClosed.put(key, range.from());
        }
    }

    /** Tries again the pair's failed fills whose delay has passed. */
    private void retryDue(BookKey key) {
        List<PendingFill> waiting = retries.get(key);
        if (waiting == null) {
            return;
        }
        Instant now = clock.instant();
        List<PendingFill> due = new ArrayList<>();
        waiting.removeIf(pending -> {
            boolean isDue = !pending.nextTryAt().isAfter(now);
            if (isDue) {
                due.add(pending);
            }
            return isDue;
        });
        if (waiting.isEmpty()) {
            retries.remove(key);
        }
        fillAll(
                key,
                due.stream()
                        .map(pending -> new Fill(pending.order(), pending.tradedAt(), pending.source(), pending))
                        .toList());
    }

    /**
     * Stores the fills of one range. The fills of a Trader are stored one after another in the order given (price-time
     * priority), those of different Traders at once, at most {@code fill-parallelism} Traders at a time, so thousands
     * of orders reached by one candle do not wait on a single connection. Returns once every fill is stored or has
     * failed; the failures are handled here, on the consumer thread, which owns the book, the retries and the
     * watermark holds.
     */
    private void fillAll(BookKey key, List<Fill> fills) {
        if (fills.isEmpty()) {
            return;
        }
        Map<UUID, List<Fill>> byAccount = new LinkedHashMap<>();
        fills.forEach(fill -> byAccount
                .computeIfAbsent(fill.order().accountId(), ignored -> new ArrayList<>())
                .add(fill));
        List<Failure> failures = new ArrayList<>();
        if (byAccount.size() == 1) {
            failures.addAll(store(byAccount.values().iterator().next()));
        } else {
            Semaphore permits = new Semaphore(properties.fillParallelism());
            List<Future<List<Failure>>> running = new ArrayList<>();
            try (ExecutorService pool =
                    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
                for (List<Fill> account : byAccount.values()) {
                    running.add(pool.submit(() -> {
                        permits.acquire();
                        try {
                            return store(account);
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (Future<List<Failure>> done : running) {
                    failures.addAll(done.get());
                }
            } catch (InterruptedException interrupted) {
                // Stopping: what was not stored is rebuilt from the database and replayed at the next start.
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException unexpected) {
                throw new IllegalStateException("paper fills of " + key + " failed", unexpected.getCause());
            }
        }
        failures.forEach(failure -> failed(key, failure));
    }

    /** Stores one Trader's fills in order; a fill that fails does not stop the ones after it. */
    private List<Failure> store(List<Fill> fills) {
        List<Failure> failures = new ArrayList<>();
        for (Fill fill : fills) {
            try {
                if (!matching.fill(fill.order(), fill.tradedAt(), fill.source())) {
                    log.debug(
                            "TR-02 paper order {} no longer works", fill.order().orderId());
                }
            } catch (RuntimeException failure) {
                failures.add(new Failure(fill, failure));
            }
        }
        return failures;
    }

    /**
     * A fill that failed is kept for a retry until the deadline, then the order is put back in the book and the pair's
     * watermark is held.
     */
    private void failed(BookKey key, Failure failure) {
        RestingOrder order = failure.fill().order();
        PendingFill previous = failure.fill().previous();
        Instant now = clock.instant();
        Instant firstFailedAt = previous == null ? now : previous.firstFailedAt();
        if (!now.isBefore(firstFailedAt.plus(properties.retryDeadline()))) {
            watermarkHeld.add(key);
            book.track(order);
            log.error(
                    "TR-02 paper order {} fill failed since {}; given up, the order waits again and the pair's"
                            + " watermark is held until a restart replays the candle that reached it",
                    order.orderId(),
                    firstFailedAt,
                    failure.cause());
            return;
        }
        log.warn(
                "TR-02 paper order {} fill failed; retried in {}: {}",
                order.orderId(),
                properties.retryDelay(),
                failure.cause().toString());
        retries.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new PendingFill(
                        order,
                        failure.fill().tradedAt(),
                        failure.fill().source(),
                        firstFailedAt,
                        now.plus(properties.retryDelay())));
    }

    private void advanceWatermark(PriceRange range) {
        try {
            matching.advanceWatermark(range.market(), range.pairId(), range.from());
        } catch (RuntimeException failure) {
            log.warn(
                    "TR-02 {} {} paper watermark not advanced to {}: {}",
                    range.market(),
                    range.pairId(),
                    range.from(),
                    failure.toString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Replay
    // ------------------------------------------------------------------------------------------

    /** Queues the start-up replay of a pair whose oldest waiting order was placed at {@code placedAt}. */
    private void startupReplay(BookKey key, Instant placedAt) {
        Instant from = minuteOf(placedAt);
        Instant afterWatermark = matching.watermark(key.market(), key.pairId())
                .map(watermark -> watermark.plus(MINUTE))
                .orElse(from);
        if (afterWatermark.isAfter(from)) {
            from = afterWatermark;
        }
        Instant oldest = minuteOf(clock.instant()).minus(properties.replay().maxWindow());
        if (from.isBefore(oldest)) {
            log.warn(
                    "TR-02 {} {}: candles from {} to {} are beyond the replay window of {} and not replayed",
                    key.market(),
                    key.pairId(),
                    from,
                    oldest,
                    properties.replay().maxWindow());
            from = oldest;
        }
        replaying.add(key);
        replays.offer(new Replay(key, from));
    }

    /** The replay thread: one pair at a time, page by page, until it has caught up with the closed candles. */
    private void runReplays() {
        while (running) {
            Replay replay;
            try {
                replay = replays.take();
                replay(replay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void replay(Replay replay) throws InterruptedException {
        BookKey key = replay.key();
        Instant cursor = replay.from();
        while (running) {
            MinuteKlineBatch batch;
            try {
                batch = market.closedMinuteKlines(key.market(), key.pairId(), cursor);
            } catch (RuntimeException failure) {
                log.warn(
                        "TR-02 {} {} paper replay failed at {}; retried in {}: {}",
                        key.market(),
                        key.pairId(),
                        cursor,
                        properties.replay().retryDelay(),
                        failure.toString());
                Thread.sleep(properties.replay().retryDelay());
                continue;
            }
            if (batch.refused()) {
                Duration wait = Duration.between(clock.instant(), batch.retryAt());
                Thread.sleep(wait.isPositive() ? wait : properties.replay().retryDelay());
                continue;
            }
            boolean advanced = false;
            for (MinuteKline kline : batch.klines()) {
                if (!kline.openTime().isBefore(cursor)) {
                    queue.offer(new Command.Range(new PriceRange(
                            kline.market(),
                            kline.pairId(),
                            kline.low(),
                            kline.high(),
                            kline.openTime(),
                            true,
                            FillSource.REPLAY)));
                    cursor = kline.openTime().plus(MINUTE);
                    advanced = true;
                }
            }
            if (!advanced) {
                break;
            }
        }
        queue.offer(new Command.ReplayDone(key, cursor));
        log.info("TR-02 {} {} paper replay done up to {}", key.market(), key.pairId(), cursor);
    }

    private void startThread(String name, Runnable task) {
        Thread thread = threads.newThread(task);
        thread.setName(name);
        thread.start();
        workers.add(thread);
    }

    private static BookKey keyOf(RestingOrder order) {
        return new BookKey(order.market(), order.pairId());
    }

    private static Instant minuteOf(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MINUTES);
    }

    /** A fill to store; {@code previous} is the retry it comes from, or {@code null} for a first try. */
    private record Fill(RestingOrder order, Instant tradedAt, FillSource source, PendingFill previous) {}

    private record Failure(Fill fill, RuntimeException cause) {}

    /** A pair's replay from the candle opened at {@code from}. */
    private record Replay(BookKey key, Instant from) {}

    /** A fill to try again, with when it first failed and when to try next. */
    private record PendingFill(
            RestingOrder order, Instant tradedAt, FillSource source, Instant firstFailedAt, Instant nextTryAt) {}

    /** What the consumer is asked to do. */
    private sealed interface Command {

        record Track(RestingOrder order) implements Command {}

        record Untrack(UUID orderId) implements Command {}

        /** A closed candle, live or replayed. */
        record Range(PriceRange range) implements Command {}

        /** Read the pair's merged forming range. */
        record Forming(BookKey key) implements Command {}

        /** A replay of the pair has caught up; {@code until} is the open time of the first candle not replayed. */
        record ReplayDone(BookKey key, Instant until) implements Command {}

        /** Wakes the consumer so it sees the stop. */
        record Stop() implements Command {}
    }
}
