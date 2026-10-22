package com.cryptopilot.paper.job;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MarketTradeListener;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.event.PaperOrderAwaitingTrigger;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.matching.PaperOrderBook;
import com.cryptopilot.paper.matching.PaperOrderBook.BookKey;
import com.cryptopilot.paper.matching.TriggerBook;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.service.PaperMatchingService;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * order, the orders that start or stop waiting, the market trades of the pairs and their 1-minute candles; a replay
 * thread fetches the closed candles the engine missed (Q-T6). Kept apart from the plan engine of the {@code trading}
 * module (Q-T1).
 *
 * <h2>Feeding</h2>
 *
 * <ul>
 *   <li>Market trades are the live path (Q-T6, TR-05): each one is matched as it comes, so an order fills within the
 *       trade's arrival and the storing of its fill, and the orders behind it in the book are reached by the next
 *       trade. The trades of a pair that arrive while the consumer is busy are merged into one waiting range, with the
 *       lowest and highest price and the time each was traded, and queued once: the stream reader never blocks, and
 *       no price is skipped. The merged range keeps each trade with its time, and an order is reached only by the
 *       trades after its placement (D-77). An order a trade reached fills at that trade's time. With {@code limit-fill-mode}
 *       {@code QUEUE} (Binance's price-time priority) an order starts behind the quantity the exchange's depth shows
 *       at its price when it starts waiting, which the trades at that price then consume.
 *   <li>An order is tracked once its placement commits, which may be after the trades that followed its placement
 *       were read into a range and ran. Each pair keeps its latest trades ({@code recent-trades}), and an order or a
 *       stop is matched against those after its placement when it is tracked, so they are not lost.
 *   <li>The forming candles of a pair fill orders only while the pair's trades are silent for {@code trade-silence}:
 *       Futures, a pair streamed without trades, or a lost trade stream. A closed candle always fills what it reached,
 *       so a trade lost on a reconnection is made up for at the candle's open time (D-78), and it moves the watermark.
 *   <li>A closed candle is queued as it comes. The forming updates of a pair are merged into one waiting range per
 *       pair, with the lowest low and highest high of its candle, and queued once: the stream reader never blocks, and
 *       a slow consumer reads the pair's latest prices rather than a backlog of them. A forming update dropped for a
 *       newer candle is not lost, as the closed update of its candle carries it in full.
 *   <li>A range fills every order of the pair it reached ({@code limit-fill-mode}, Q-T8), at the order's price, at the
 *       time the range reached it, under the Trader's lock, in price-time priority; the fills of different Traders are stored at once
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
 * <h2>Stop limits and trailing stops</h2>
 *
 * <p>The stop limits and trailing stops wait in a {@link TriggerBook} the same ranges reach, before the limit orders
 * are filled. A range that triggers one has it triggered under its Trader's lock: live, it meets the current book at
 * once as a limit order placed then does, and what is left of it waits in the book; replayed, it waits at its price
 * for the candles that follow. A trigger that fails leaves the order watched, so the next range triggers it again.
 * The best prices the trailing stops followed to are stored once a minute, on each closed candle, and a restart
 * resumes from them; the replay from the watermark covers the minute not stored.
 *
 * <p>Rule: TR-02, TR-05, TR-09, TR-11; NSF-07; Q-T1, Q-T6, Q-T8; A-04; D-77, D-78; ADR-011.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer) and ch. 7 (cancellation and shutdown).
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (a watermark never passes
 * event time whose input is not complete).
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley,
 * "Aggregator" (the forming updates of a candle merged into one).
 */
@Component
public class PaperMatchingWorker implements MinuteKlineListener, MarketTradeListener {

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

    /** The merged market trades of each pair, waiting for the consumer; its key is queued once while present. */
    private final Map<BookKey, PriceRange> trades = new ConcurrentHashMap<>();

    /** Owned by the consumer thread once it starts; written by {@link #start()} before that. */
    private final PaperOrderBook book;

    /** The stop limits and trailing stops; owned like {@link #book}. */
    private final TriggerBook triggers = new TriggerBook();

    /** The latest live trades of each pair the consumer matched, earliest first; owned like {@link #book}. */
    private final Map<BookKey, Deque<PriceRange.Print>> recentTrades = new HashMap<>();

    /** When the consumer last read a trade of each pair. */
    private final Map<BookKey, Instant> lastTrade = new HashMap<>();

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
        this.book = new PaperOrderBook(properties.limitFillMode());
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
        List<TriggerOrder> waiting = matching.triggerOrders();
        for (TriggerOrder order : waiting) {
            triggers.track(order);
            oldest.merge(
                    new BookKey(order.market(), order.pairId()), order.placedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }
        if (properties.replay().enabled()) {
            oldest.forEach((key, placedAt) -> startupReplay(key, placedAt));
        }
        running = true;
        startThread("paper-matching", this::drain);
        startThread("paper-replay", this::runReplays);
        log.info(
                "TR-02 paper matching started with {} orders and {} waiting for a trigger; {} pairs to replay",
                resting.size(),
                waiting.size(),
                replaying.size());
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
        return queue.isEmpty() && forming.isEmpty() && trades.isEmpty() && replays.isEmpty();
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

    /** Hands a market trade to the consumer without waiting; see the class comment. */
    @Override
    public void onTrade(MarketTrade trade) {
        if (!running) {
            return;
        }
        PriceRange range = PriceRange.of(trade);
        BookKey key = new BookKey(range.market(), range.pairId());
        AtomicBoolean fresh = new AtomicBoolean();
        trades.compute(key, (ignored, waiting) -> {
            fresh.set(waiting == null);
            return waiting == null ? range : waiting.withTrades(range);
        });
        if (fresh.get()) {
            queue.offer(new Command.Trades(key));
        }
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

    /** A stop limit or a trailing stop waits for its trigger. */
    @TransactionalEventListener
    public void onAwaitingTrigger(PaperOrderAwaitingTrigger event) {
        if (running) {
            queue.offer(new Command.TrackTrigger(event.order()));
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
            case Command.Track track -> {
                book.track(track.order(), queueAhead(track.order()));
                catchUp(track.order());
            }
            case Command.TrackTrigger track -> {
                triggers.track(track.order());
                catchUp(track.order());
            }
            case Command.Untrack untrack -> {
                book.untrack(untrack.orderId());
                triggers.untrack(untrack.orderId());
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
            case Command.Trades tradesKey -> {
                PriceRange range = trades.remove(tradesKey.key());
                if (range != null) {
                    onTrades(tradesKey.key(), range);
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
        boolean tracked = hasOrders(key);
        if (range.closed() || !tradesFlow(key)) {
            fillReached(key, range);
        }
        if (range.closed()) {
            saveExtremes();
        }
        boolean replayed = range.source() == FillSource.REPLAY;
        if (range.closed()
                && (tracked || replayed)
                && (replayed || !replaying.contains(key))
                && !retries.containsKey(key)
                && !watermarkHeld.contains(key)) {
            advanceWatermark(range);
        }
    }

    /** Fills at once what the pair's latest trades reached; trades never move the watermark. */
    private void onTrades(BookKey key, PriceRange range) {
        lastTrade.put(key, clock.instant());
        retryDue(key);
        fillReached(key, range);
        remember(key, range.prints());
    }

    /** Keeps a pair's latest trades, within {@code recent-trades.window} of the newest and at most {@code max-trades}. */
    private void remember(BookKey key, List<PriceRange.Print> prints) {
        Deque<PriceRange.Print> kept = recentTrades.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        prints.forEach(kept::addLast);
        Instant oldest = kept.getLast().at().minus(properties.recentTrades().window());
        while (kept.size() > properties.recentTrades().maxTrades()
                || kept.getFirst().at().isBefore(oldest)) {
            kept.removeFirst();
        }
    }

    /**
     * The pair's recent trades after {@code placedAt}, earliest first. Strictly after: a trade at that instant is the
     * one a stop triggered on, which its limit already met on arrival.
     */
    private List<PriceRange.Print> tradesAfter(BookKey key, Instant placedAt) {
        Deque<PriceRange.Print> kept = recentTrades.get(key);
        if (kept == null) {
            return List.of();
        }
        return kept.stream()
                .filter(print -> print.at().isAfter(placedAt))
                .sorted(Comparator.comparing(PriceRange.Print::at))
                .toList();
    }

    /** Fills a LIMIT order just tracked that trades read before it was tracked reached (D-77). */
    private void catchUp(RestingOrder order) {
        BookKey key = keyOf(order);
        List<PriceRange.Print> after = tradesAfter(key, order.placedAt());
        if (after.isEmpty()) {
            return;
        }
        book.catchUp(order.orderId(), after)
                .ifPresent(reached ->
                        fillAll(key, List.of(new Fill(reached.order(), reached.at(), FillSource.LIVE, null))));
    }

    /** Triggers a stop just watched that trades read before it was watched reached (D-77). */
    private void catchUp(TriggerOrder order) {
        BookKey key = new BookKey(order.market(), order.pairId());
        List<PriceRange.Print> after = tradesAfter(key, order.placedAt());
        if (after.isEmpty()) {
            return;
        }
        triggers.catchUp(order.orderId(), PriceRange.ofPrints(key.market(), key.pairId(), after))
                .ifPresent(fired -> trigger(fired, FillSource.LIVE, null));
    }

    /** Whether a trade of the pair was read within {@code trade-silence}, so its forming candles are left to them. */
    private boolean tradesFlow(BookKey key) {
        Instant last = lastTrade.get(key);
        return last != null && clock.instant().isBefore(last.plus(properties.tradeSilence()));
    }

    /** Whether any order of the pair waits, at its price or for a trigger. */
    private boolean hasOrders(BookKey key) {
        return book.hasOrders(key.market(), key.pairId()) || triggers.hasOrders(key.market(), key.pairId());
    }

    /**
     * Triggers the stop limits and trailing stops the range reached, earliest first, one after another; a trigger that
     * fails leaves its order watched for the next range.
     */
    private void triggerReached(PriceRange range) {
        for (TriggerBook.Triggered fired : triggers.onRange(range)) {
            trigger(fired, range.source(), range.open());
        }
    }

    /** Triggers one stop under its Trader's lock; on a failure it is watched again for the next price. */
    private void trigger(TriggerBook.Triggered fired, FillSource source, BigDecimal candleOpen) {
        try {
            if (!matching.trigger(fired.order(), fired.at(), fired.stopPrice(), source, candleOpen)) {
                log.debug(
                        "TR-09 paper order {} no longer waits for a trigger",
                        fired.order().orderId());
            }
        } catch (RuntimeException failure) {
            triggers.track(fired.order());
            log.warn(
                    "TR-09 paper order {} trigger failed; it waits for the next price: {}",
                    fired.order().orderId(),
                    failure.toString());
        }
    }

    /** Stores the best prices the trailing stops followed to since the last closed candle. */
    private void saveExtremes() {
        Map<UUID, BigDecimal> moved = triggers.movedExtremes();
        if (moved.isEmpty()) {
            return;
        }
        try {
            matching.saveExtremes(moved);
        } catch (RuntimeException failure) {
            log.warn("TR-11 best prices of {} trailing stops not stored: {}", moved.size(), failure.toString());
        }
    }

    /**
     * Triggers what the range reached, then takes from the book the orders it reached and stores their fills, each
     * at the time it was reached.
     */
    private void fillReached(BookKey key, PriceRange range) {
        triggerReached(range);
        fillAll(
                key,
                book.reach(range, this::depthAt).stream()
                        .map(reached -> new Fill(reached.order(), reached.at(), range.source(), null))
                        .toList());
    }

    /** The quantity the exchange's book shows at an order's price now, ahead of it; {@code null} when not known. */
    private BigDecimal queueAhead(RestingOrder order) {
        return depthAt(order.market(), order.pairId(), order.side(), order.limitPrice())
                .orElse(null);
    }

    /**
     * The quantity at {@code price} on the side an order of {@code side} waits, from the pair's current depth: the
     * level's quantity, zero when the price lies within the levels shown with none at it, empty when it lies beyond
     * them or no current depth is held.
     */
    private Optional<BigDecimal> depthAt(MarketType market, UUID pairId, OrderSide side, BigDecimal price) {
        Optional<OrderBookDepth> depth = this.market.currentDepth(market, pairId);
        if (depth.isEmpty()) {
            return Optional.empty();
        }
        List<OrderBookDepth.PriceLevel> levels =
                side == OrderSide.BUY ? depth.get().bids() : depth.get().asks();
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        for (OrderBookDepth.PriceLevel level : levels) {
            if (level.price().compareTo(price) == 0) {
                return Optional.of(level.quantity());
            }
        }
        // Bids are listed best (highest) first, asks best (lowest) first.
        BigDecimal worst = levels.getLast().price();
        boolean within = side == OrderSide.BUY ? price.compareTo(worst) > 0 : price.compareTo(worst) < 0;
        return within ? Optional.of(BigDecimal.ZERO) : Optional.empty();
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
                && hasOrders(key)
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
            prefetchQuoteRates(key, batch.klines(), cursor);
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
                            FillSource.REPLAY,
                            kline.open())));
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

    /** Before the candles are queued, so their fills read the quote rates without a network call (Q-52). */
    private void prefetchQuoteRates(BookKey key, List<MinuteKline> klines, Instant from) {
        List<MinuteKline> fresh = klines.stream()
                .filter(kline -> !kline.openTime().isBefore(from))
                .toList();
        if (fresh.isEmpty()) {
            return;
        }
        try {
            matching.prefetchQuoteRates(
                    key.market(),
                    key.pairId(),
                    fresh.get(0).openTime(),
                    fresh.get(fresh.size() - 1).openTime());
        } catch (RuntimeException failure) {
            log.warn("Q-52 {} {} quote rates not prefetched: {}", key.market(), key.pairId(), failure.toString());
        }
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

        record TrackTrigger(TriggerOrder order) implements Command {}

        record Untrack(UUID orderId) implements Command {}

        /** A closed candle, live or replayed. */
        record Range(PriceRange range) implements Command {}

        /** Read the pair's merged forming range. */
        record Forming(BookKey key) implements Command {}

        /** Read the pair's merged market trades. */
        record Trades(BookKey key) implements Command {}

        /** A replay of the pair has caught up; {@code until} is the open time of the first candle not replayed. */
        record ReplayDone(BookKey key, Instant until) implements Command {}

        /** Wakes the consumer so it sees the stop. */
        record Stop() implements Command {}
    }
}
