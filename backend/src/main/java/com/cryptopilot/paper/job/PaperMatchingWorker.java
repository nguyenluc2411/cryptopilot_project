package com.cryptopilot.paper.job;

import com.cryptopilot.market.MarkPrice;
import com.cryptopilot.market.MarkPriceListener;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MarketTradeListener;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.event.FuturesAccountChanged;
import com.cryptopilot.paper.event.PaperOrderAwaitingMarkTrigger;
import com.cryptopilot.paper.event.PaperOrderAwaitingTrigger;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.matching.LiquidationBook;
import com.cryptopilot.paper.matching.PaperOrderBook;
import com.cryptopilot.paper.matching.PaperOrderBook.BookKey;
import com.cryptopilot.paper.matching.TriggerBook;
import com.cryptopilot.paper.model.CrossAccount;
import com.cryptopilot.paper.model.LiquidationLevel;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.ReplayedCandle;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *       trade. The trades of a pair that arrive while the consumer is busy are gathered one by one ({@link
 *       PendingTrades}), each append costing the same however many wait, and queued once: the stream reader never
 *       blocks, does no work that grows with the backlog, and no price is skipped. Past {@code pending-trades-max}
 *       the oldest are folded into the low, the high and the volumes. The merged range keeps each trade with its time, and an order is reached only by the
 *       trades after its placement (D-77). An order a trade reached fills at that trade's time. With {@code limit-fill-mode}
 *       {@code QUEUE} (Binance's price-time priority) an order starts behind the quantity the exchange's depth shows
 *       at its price when it starts waiting, which the trades at that price then consume.
 *   <li>An order is tracked once its placement commits, which may be after the trades that followed its placement
 *       were read into a range and ran. A pair with an order tracked, or published and not tracked yet, keeps its
 *       latest trades ({@code recent-trades}), and an order or a stop is matched against those after its placement
 *       when it is tracked, so they are not lost. A pair without either keeps none.
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
 * <h2>Mark prices (Futures)</h2>
 *
 * <p>The mark prices of a Futures pair run its conditional orders on the mark and its isolated liquidations, in arrival
 * order (TR-20, TR-22). A pair with either keeps a mark watermark of its own: a minute of marks is whole once a mark of
 * the next minute follows without a gap (TR-42, plan 9.5). A mark more than {@code mark-max-age} after the previous
 * one, or a restart, starts a catch-up: the pair is {@code MARK_STALE} for every request, the live marks are held,
 * and the replay thread fetches the closed 1-minute mark candles from the mark watermark, each run as open, the
 * extreme nearer the open, the other extreme, close. What a replayed mark triggers or liquidates is applied at the
 * time it is applied, on the state of now, never back-dated. The catch-up ends once the candle of the minute the first
 * held mark came in is replayed, which may mean waiting for that minute to close, and two live marks follow: the held
 * marks inside replayed candles are dropped, the others run in order. The last price candles are not replayed for a
 * lost mark stream, as the trades went on.
 *
 * <h2>Cross liquidation (Futures)</h2>
 *
 * <p>The engine keeps a snapshot of the cross side of each Trader with cross positions (TR-23, plan 9.3), read again
 * whenever a committed change of the account is reported, and the latest mark of each pair. A mark of a pair screens
 * the Traders with a cross position on it, without the database: a cross margin balance at or below the maintenance
 * margin of their cross positions asks the matching service for a step of the liquidation, decided again on the
 * database under the Trader's lock. A position to close on another pair is a {@code ForceClose} queued for that pair,
 * so it closes in its turn, at that pair's mark. A liquidation still running after {@code liquidation-max-duration},
 * or at start, is resumed by {@link #resumeLiquidations()}.
 *
 * <p>The same screening asks for a margin call (TR-43): of an isolated position whose margin call price the mark
 * reached, and of a Trader whose cross margin ratio is at or above {@code margin-call-ratio}; each at most once per
 * {@code margin-call-cooldown}, kept in memory, so a restart may call once more.
 *
 * <p>Rule: TR-02, TR-05, TR-09, TR-11, TR-23, TR-42; NSF-07; Q-T1, Q-T6, Q-T8; A-04; D-77, D-78; ADR-011.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer), ch. 7 (cancellation and shutdown) and ch. 11 (lock scope and contention).
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (a watermark never passes
 * event time whose input is not complete).
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley,
 * "Aggregator" (the forming updates of a candle merged into one).
 */
@Component
public class PaperMatchingWorker implements MinuteKlineListener, MarketTradeListener, MarkPriceListener {

    private static final Logger log = LoggerFactory.getLogger(PaperMatchingWorker.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final PaperMatchingService matching;
    private final MarketApi market;
    private final PaperMatchingProperties properties;
    private final PaperFuturesProperties futures;
    private final Clock clock;
    private final ThreadFactory threads;

    private final BlockingQueue<Command> queue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Replay> replays = new LinkedBlockingQueue<>();

    /** The merged forming range of each pair, waiting for the consumer; its key is queued once while present. */
    private final Map<BookKey, PriceRange> forming = new ConcurrentHashMap<>();

    /**
     * The mark prices of each Futures pair waiting for the consumer, in arrival order; its key is queued once while
     * present. Every one is kept: a trailing stop needs each peak, and a mark that crossed a trigger and came back must
     * still reach it. The exchange sends one a second per pair, so the wait stays short.
     */
    private final Map<BookKey, List<MarkPrice>> marks = new ConcurrentHashMap<>();

    /** The market trades of each pair, waiting for the consumer; its key is queued once while present. */
    private final Map<BookKey, PendingTrades> trades = new ConcurrentHashMap<>();

    /** When a pair's waiting trades were last logged as folded; written by the stream thread. */
    private final Map<BookKey, Instant> foldWarnedAt = new ConcurrentHashMap<>();

    /**
     * How many orders of each pair were published to wait, at their price or for a trigger, and not tracked yet: their
     * pair keeps its recent trades meanwhile. Counted from inside the placing transaction, as the trades the
     * catch-up needs may be read before it commits.
     */
    private final Map<BookKey, Integer> untracked = new ConcurrentHashMap<>();

    /** Owned by the consumer thread once it starts; written by {@link #start()} before that. */
    private final PaperOrderBook book;

    /** The stop limits and trailing stops, and the Futures conditional orders on the last price; owned like {@link #book}. */
    private final TriggerBook triggers = new TriggerBook();

    /** The Futures conditional orders on the mark price (TR-20); owned like {@link #book}. */
    private final TriggerBook markTriggers = new TriggerBook();

    /** The liquidation levels of the isolated Futures positions (TR-22); owned like {@link #book}. */
    private final LiquidationBook liquidations = new LiquidationBook();

    /** The latest live trades of each pair the consumer matched, earliest first; owned like {@link #book}. */
    private final Map<BookKey, Deque<PriceRange.Print>> recentTrades = new HashMap<>();

    /** When a pair last lost a recent trade still within the window; owned like {@link #book}. */
    private final Map<BookKey, Instant> evictionWarnedAt = new HashMap<>();

    /** When the consumer last read a trade of each pair. */
    private final Map<BookKey, Instant> lastTrade = new HashMap<>();

    /** The cross side of each Trader with cross positions, as last read (TR-23); owned like {@link #book}. */
    private final Map<UUID, CrossAccount> crossAccounts = new HashMap<>();

    /** The Traders with a cross position on each pair (TR-23); owned like {@link #book}. */
    private final Map<UUID, Set<UUID>> crossHolders = new HashMap<>();

    /** When a margin call was last sent, by isolated position or by cross Trader (TR-43); owned like {@link #book}. */
    private final Map<UUID, Instant> marginCalledAt = new HashMap<>();

    /** The latest mark of each Futures pair, live or replayed (TR-23); owned like {@link #book}. */
    private final Map<UUID, BigDecimal> latestMarks = new HashMap<>();

    /** When the consumer last ran a live mark of each Futures pair (TR-42); owned like {@link #book}. */
    private final Map<BookKey, Instant> lastMark = new HashMap<>();

    /** The Futures pairs catching up on missed marks (TR-42); owned like {@link #book} once the consumer starts. */
    private final Map<BookKey, MarkCatchUp> markCatchUps = new HashMap<>();

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
            PaperMatchingService matching,
            MarketApi market,
            PaperMatchingProperties properties,
            PaperFuturesProperties futures,
            Clock clock) {
        this(matching, market, properties, futures, clock, Thread.ofVirtual().factory());
    }

    PaperMatchingWorker(
            PaperMatchingService matching,
            MarketApi market,
            PaperMatchingProperties properties,
            PaperFuturesProperties futures,
            Clock clock,
            ThreadFactory threads) {
        this.matching = matching;
        this.market = market;
        this.properties = properties;
        this.futures = futures;
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
        Map<BookKey, Instant> oldestOnMark = new HashMap<>();
        for (TriggerOrder order : matching.markTriggerOrders()) {
            markTriggers.track(order);
            oldestOnMark.merge(
                    new BookKey(order.market(), order.pairId()), order.placedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }
        // A position's level has no time of its own: without a mark watermark only the minute running now is caught up.
        for (LiquidationLevel level : matching.liquidationLevels()) {
            liquidations.track(level);
            oldestOnMark.putIfAbsent(new BookKey(MarketType.FUTURES, level.pairId()), clock.instant());
        }
        matching.crossAccounts().forEach(this::trackCross);
        if (properties.replay().enabled()) {
            oldest.forEach((key, placedAt) -> startupReplay(key, placedAt));
            oldestOnMark.forEach((key, placedAt) -> startupMarkReplay(key, placedAt));
        }
        // A liquidation that ran when the engine stopped goes on now.
        queue.offer(new Command.ResumeLiquidations(clock.instant()));
        running = true;
        startThread("paper-matching", this::drain);
        startThread("paper-replay", this::runReplays);
        log.info(
                "TR-02 paper matching started with {} orders and {} waiting for a trigger; {} pairs to replay, {} on"
                        + " the mark",
                resting.size(),
                waiting.size(),
                replaying.size(),
                markCatchUps.size());
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

    /**
     * Resumes the cross liquidations that started more than {@code liquidation-max-duration} ago and still run: each
     * account is judged again, and the pair whose position closes next gets a {@code ForceClose} (TR-23, plan 9.3).
     */
    public void resumeLiquidations() {
        if (running) {
            queue.offer(new Command.ResumeLiquidations(clock.instant().minus(futures.liquidationMaxDuration())));
        }
    }

    /** Whether nothing is queued for the consumer or the replay thread; for tests. */
    boolean isIdle() {
        return queue.isEmpty() && forming.isEmpty() && trades.isEmpty() && marks.isEmpty() && replays.isEmpty();
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

    /**
     * Hands a Futures mark price to the consumer without waiting (TR-20): the mark goes through the same queue and the
     * same thread as the pair's trades, so a mark trigger and a last-price trigger keep the order they arrived in (plan
     * 9.1). Marks that arrive while the consumer is busy wait in order and are all applied.
     */
    @Override
    public void onMarkPrice(MarkPrice mark) {
        if (!running) {
            return;
        }
        BookKey key = new BookKey(MarketType.FUTURES, mark.pairId());
        AtomicBoolean fresh = new AtomicBoolean();
        marks.compute(key, (ignored, waiting) -> {
            List<MarkPrice> gathered = waiting;
            if (gathered == null) {
                gathered = new ArrayList<>();
                fresh.set(true);
            }
            gathered.add(mark);
            return gathered;
        });
        if (fresh.get()) {
            queue.offer(new Command.Mark(key));
        }
    }

    /** Hands a market trade to the consumer without waiting; see the class comment. */
    @Override
    public void onTrade(MarketTrade trade) {
        if (!running) {
            return;
        }
        BookKey key = new BookKey(trade.market(), trade.pairId());
        PriceRange.Print print =
                new PriceRange.Print(trade.price(), trade.quantity(), trade.buyerMaker(), trade.tradeTime());
        AtomicBoolean fresh = new AtomicBoolean();
        AtomicBoolean folded = new AtomicBoolean();
        // Constant work inside the bin: one append, no copy of what already waits.
        trades.compute(key, (ignored, waiting) -> {
            PendingTrades gathered = waiting;
            if (gathered == null) {
                fresh.set(true);
                gathered = new PendingTrades(key.market(), key.pairId(), properties.pendingTradesMax());
            }
            folded.set(gathered.add(print));
            return gathered;
        });
        if (fresh.get()) {
            queue.offer(new Command.Trades(key));
        }
        if (folded.get()) {
            warnFolded(key);
        }
    }

    /** Logs, at most once a minute per pair, that its waiting trades outgrew {@code pending-trades-max}. */
    private void warnFolded(BookKey key) {
        Instant now = clock.instant();
        Instant last = foldWarnedAt.get(key);
        if (last != null && now.isBefore(last.plus(MINUTE))) {
            return;
        }
        foldWarnedAt.put(key, now);
        log.warn(
                "TR-05 {} {}: more than {} trades wait for the paper matching consumer; the oldest are folded into"
                        + " the low, the high and the volumes",
                key.market(),
                key.pairId(),
                properties.pendingTradesMax());
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

    /**
     * An order is published to wait at its price, inside the transaction that places it: its pair keeps its recent
     * trades from now until the order is tracked, so the catch-up finds the trades read before the commit.
     */
    @EventListener
    public void onRestedPublished(PaperOrderRested event) {
        expect(keyOf(event.order()));
    }

    /** As {@link #onRestedPublished}, for a stop published to wait for its trigger. */
    @EventListener
    public void onAwaitingTriggerPublished(PaperOrderAwaitingTrigger event) {
        expect(new BookKey(event.order().market(), event.order().pairId()));
    }

    /** A placement that rolled back tracks nothing. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onRestedRolledBack(PaperOrderRested event) {
        tracked(keyOf(event.order()));
    }

    /** As {@link #onRestedRolledBack}, for a stop. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onAwaitingTriggerRolledBack(PaperOrderAwaitingTrigger event) {
        tracked(new BookKey(event.order().market(), event.order().pairId()));
    }

    /** A LIMIT order that did not execute on arrival waits in the book. */
    @TransactionalEventListener
    public void onRested(PaperOrderRested event) {
        if (running) {
            queue.offer(new Command.Track(event.order()));
        } else {
            tracked(keyOf(event.order()));
        }
    }

    /** A stop limit or a trailing stop waits for its trigger. */
    @TransactionalEventListener
    public void onAwaitingTrigger(PaperOrderAwaitingTrigger event) {
        if (running) {
            queue.offer(new Command.TrackTrigger(event.order()));
        } else {
            tracked(new BookKey(event.order().market(), event.order().pairId()));
        }
    }

    /** Counts an order of the pair published to wait; only inside a transaction, whose commit or rollback follows. */
    private void expect(BookKey key) {
        if (running && TransactionSynchronizationManager.isSynchronizationActive()) {
            untracked.merge(key, 1, Integer::sum);
        }
    }

    /** One order of the pair counted by {@link #expect} is tracked, or will never be. */
    private void tracked(BookKey key) {
        untracked.computeIfPresent(key, (ignored, count) -> count > 1 ? count - 1 : null);
    }

    /** A Futures conditional order on the mark price waits for its trigger (TR-20). */
    @TransactionalEventListener
    public void onAwaitingMarkTrigger(PaperOrderAwaitingMarkTrigger event) {
        if (running) {
            queue.offer(new Command.TrackMarkTrigger(event.order()));
        }
    }

    /**
     * A committed change of a Trader's position or isolated wallet on a pair: its liquidation levels are read again on
     * the consumer thread, which alone owns the book (TR-22, plan 9.6).
     */
    @TransactionalEventListener
    public void onAccountChanged(FuturesAccountChanged event) {
        if (running) {
            queue.offer(new Command.AccountChanged(event.userId(), event.pairId()));
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
                BookKey key = keyOf(track.order());
                book.track(track.order(), queueAhead(track.order()));
                catchUp(track.order());
                tracked(key);
                forgetIfIdle(key);
            }
            case Command.TrackMarkTrigger track -> markTriggers.track(track.order());
            case Command.AccountChanged changed -> {
                refreshLevels(changed.userId(), changed.pairId());
                refreshCross(changed.userId());
            }
            case Command.ForceClose force -> crossStep(force.userId(), force.pairId(), clock.instant());
            case Command.ResumeLiquidations resume -> resume(resume.startedBefore());
            case Command.Mark markKey -> {
                List<MarkPrice> waiting = marks.remove(markKey.key());
                if (waiting != null) {
                    waiting.forEach(mark -> onLiveMark(markKey.key(), mark));
                }
            }
            case Command.MarkCandle candle -> onMarkCandle(candle.key(), candle.kline());
            case Command.MarkReplayDone done -> {
                MarkCatchUp catchUp = markCatchUps.get(done.key());
                if (catchUp != null) {
                    catchUp.replayed(done.until());
                    finishCatchUp(done.key(), catchUp, false);
                }
            }
            case Command.TrackTrigger track -> {
                BookKey key = new BookKey(track.order().market(), track.order().pairId());
                triggers.track(track.order());
                catchUp(track.order());
                tracked(key);
                forgetIfIdle(key);
            }
            case Command.Untrack untrack -> {
                book.untrack(untrack.orderId());
                triggers.untrack(untrack.orderId());
                markTriggers.untrack(untrack.orderId());
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
                PendingTrades waiting = trades.remove(tradesKey.key());
                if (waiting != null) {
                    // Built here, once, on the consumer thread: never under the map bin the stream appends in.
                    onTrades(tradesKey.key(), waiting.drain());
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

    /**
     * Keeps the latest trades of a pair with an order tracked or about to be: within {@code recent-trades.window} of
     * the newest first, then at most {@code max-trades}. A trade still within the window that the count drops is
     * logged, as an order tracked late could have needed it.
     */
    private void remember(BookKey key, List<PriceRange.Print> prints) {
        if (forgetIfIdle(key)) {
            return;
        }
        Deque<PriceRange.Print> kept = recentTrades.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        prints.forEach(kept::addLast);
        Instant oldest = kept.getLast().at().minus(properties.recentTrades().window());
        while (kept.getFirst().at().isBefore(oldest)) {
            kept.removeFirst();
        }
        int evicted = 0;
        while (kept.size() > properties.recentTrades().maxTrades()) {
            kept.removeFirst();
            evicted++;
        }
        if (evicted > 0) {
            warnEvicted(key, evicted);
        }
    }

    /** Drops the recent trades of a pair with no order tracked and none about to be; returns whether it is idle. */
    private boolean forgetIfIdle(BookKey key) {
        if (hasOrders(key) || untracked.containsKey(key)) {
            return false;
        }
        recentTrades.remove(key);
        evictionWarnedAt.remove(key);
        return true;
    }

    /** Logs, at most once a minute per pair, recent trades dropped while still within the window. */
    private void warnEvicted(BookKey key, int evicted) {
        Instant now = clock.instant();
        Instant last = evictionWarnedAt.get(key);
        if (last != null && now.isBefore(last.plus(MINUTE))) {
            return;
        }
        evictionWarnedAt.put(key, now);
        log.warn(
                "TR-05 {} {}: {} recent trades within {} dropped to keep {}; an order tracked late may miss them",
                key.market(),
                key.pairId(),
                evicted,
                properties.recentTrades().window(),
                properties.recentTrades().maxTrades());
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
                        fillAll(key, List.of(new Fill(reached.order(), reached.at(), FillSource.LIVE, null, null))));
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

    /**
     * A mark price of a Futures pair: first the conditional orders it reached, earliest placed first (TR-20), then the
     * isolated positions whose liquidation price it reached, the furthest first (TR-22). A stop above a long's
     * liquidation price is on the mark's way down before it, so it goes first (plan 8.4); a position it closed is then
     * found closed and not liquidated.
     */
    private void onMark(BookKey key, MarkPrice mark, FillSource source) {
        triggerOnMark(key, mark, source);
        // A replayed mark acts now, on the state of now (plan 9.5): nothing is back-dated.
        Instant at = source == FillSource.REPLAY ? clock.instant() : mark.eventTime();
        for (LiquidationLevel level : liquidations.reached(key.pairId(), mark.price())) {
            try {
                if (!matching.liquidate(level, mark.price(), at)) {
                    refreshLevels(level.userId(), level.pairId());
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "TR-22 liquidation of position {} failed; it is tried at the next mark: {}",
                        level.positionId(),
                        failure.toString());
            }
        }
        for (LiquidationLevel level : liquidations.marginCalls(key.pairId(), mark.price())) {
            marginCall(level.userId(), level.positionId(), mark.price(), at);
        }
        latestMarks.put(key.pairId(), mark.price());
        screenCross(key.pairId(), at);
    }

    /**
     * Asks for a margin call of an isolated position, or of a Trader's cross positions when {@code positionId} is
     * {@code null}, unless one was sent within {@code margin-call-cooldown} (TR-43).
     */
    private void marginCall(UUID userId, UUID positionId, BigDecimal mark, Instant at) {
        UUID key = positionId != null ? positionId : userId;
        Instant last = marginCalledAt.get(key);
        if (last != null && at.isBefore(last.plus(futures.marginCallCooldown()))) {
            return;
        }
        try {
            if (matching.marginCall(userId, positionId, mark, at)) {
                marginCalledAt.put(key, at);
            }
        } catch (RuntimeException failure) {
            log.warn("TR-43 margin call of user {} not sent: {}", userId, failure.toString());
        }
    }

    /**
     * Screens the Traders with a cross position on the pair at the latest marks, from their snapshots: one whose cross
     * margin balance is at or below the maintenance margin gets a step of the liquidation (TR-23, plan 9.3).
     */
    private void screenCross(UUID pairId, Instant at) {
        Set<UUID> holders = crossHolders.get(pairId);
        if (holders == null) {
            return;
        }
        for (UUID userId : List.copyOf(holders)) {
            CrossAccount account = crossAccounts.get(userId);
            Optional<BigDecimal[]> margin = account == null ? Optional.empty() : crossMargin(account);
            if (margin.isEmpty()) {
                continue;
            }
            BigDecimal balance = margin.get()[0];
            BigDecimal maintenance = margin.get()[1];
            if (balance.compareTo(maintenance) <= 0) {
                crossStep(userId, pairId, at);
            } else if (maintenance.compareTo(balance.multiply(futures.marginCallRatio())) >= 0) {
                marginCall(userId, null, null, at);
            }
        }
    }

    /**
     * The snapshot's cross margin balance and maintenance margin at the latest marks, in that order; empty while a pair
     * of it has no mark yet.
     */
    private Optional<BigDecimal[]> crossMargin(CrossAccount account) {
        BigDecimal balance = account.crossWalletBalance();
        BigDecimal maintenance = BigDecimal.ZERO;
        for (CrossAccount.Leg leg : account.legs()) {
            BigDecimal mark = latestMarks.get(leg.pairId());
            if (mark == null || leg.tiers().isEmpty()) {
                return Optional.empty();
            }
            balance = balance.add(
                    FuturesMarginMath.unrealizedPnl(leg.direction(), leg.quantity(), leg.entryPrice(), mark));
            maintenance = maintenance.add(FuturesMarginMath.liquidationMaintenance(
                    leg.tiers(), leg.quantity().multiply(mark)));
        }
        return Optional.of(new BigDecimal[] {balance, maintenance});
    }

    /**
     * One step of a Trader's cross liquidation for the pair, or for none; a position to close on another pair is
     * queued for it. The snapshot is read again at once, so the next mark does not screen the old one.
     */
    private void crossStep(UUID userId, UUID pairId, Instant at) {
        Optional<UUID> next;
        try {
            next = matching.liquidateCross(userId, pairId, at);
        } catch (RuntimeException failure) {
            log.warn(
                    "TR-23 cross liquidation step of user {} on {} failed; tried again at the next mark or by the safety"
                            + " job: {}",
                    userId,
                    pairId,
                    failure.toString());
            return;
        }
        refreshCross(userId);
        // The same pair named again means its close did not go through: the safety job tries again.
        next.filter(other -> !other.equals(pairId))
                .ifPresent(other -> queue.offer(new Command.ForceClose(userId, other)));
    }

    /** Judges again every liquidation that started before {@code startedBefore} and still runs. */
    private void resume(Instant startedBefore) {
        List<UUID> running;
        try {
            running = matching.liquidatingSince(startedBefore);
        } catch (RuntimeException failure) {
            log.warn("TR-23 running cross liquidations not read: {}", failure.toString());
            return;
        }
        for (UUID userId : running) {
            log.info("TR-23 cross liquidation of user {} resumed", userId);
            crossStep(userId, null, clock.instant());
        }
    }

    /** Reads the Trader's cross side again, as the database holds it now. */
    private void refreshCross(UUID userId) {
        try {
            Optional<CrossAccount> account = matching.crossAccount(userId);
            untrackCross(userId);
            account.ifPresent(this::trackCross);
        } catch (RuntimeException failure) {
            log.warn("TR-23 cross positions of user {} not read: {}", userId, failure.toString());
        }
    }

    private void trackCross(CrossAccount account) {
        crossAccounts.put(account.userId(), account);
        account.legs().forEach(leg -> crossHolders
                .computeIfAbsent(leg.pairId(), ignored -> new HashSet<>())
                .add(account.userId()));
    }

    private void untrackCross(UUID userId) {
        CrossAccount previous = crossAccounts.remove(userId);
        if (previous == null) {
            return;
        }
        for (CrossAccount.Leg leg : previous.legs()) {
            Set<UUID> holders = crossHolders.get(leg.pairId());
            if (holders != null) {
                holders.remove(userId);
                if (holders.isEmpty()) {
                    crossHolders.remove(leg.pairId());
                }
            }
        }
    }

    /**
     * A live mark: held while the pair catches up; otherwise one that comes too long after the previous starts a
     * catch-up (TR-42), and any other runs, and closes the previous mark's minute when it opens a new one.
     */
    private void onLiveMark(BookKey key, MarkPrice mark) {
        MarkCatchUp catchUp = markCatchUps.get(key);
        if (catchUp != null) {
            catchUp.hold(mark);
            finishCatchUp(key, catchUp, true);
            return;
        }
        Instant last = lastMark.get(key);
        boolean gap = last != null && mark.eventTime().isAfter(last.plus(futures.markMaxAge()));
        if (gap && properties.replay().enabled() && watchesMarks(key)) {
            log.warn(
                    "TR-42 FUTURES {}: no mark from {} to {}; replaying the mark candles from {}",
                    key.pairId(),
                    last,
                    mark.eventTime(),
                    minuteOf(last));
            startCatchUp(key, minuteOf(last)).hold(mark);
            return;
        }
        onMark(key, mark, FillSource.LIVE);
        if (last == null || mark.eventTime().isAfter(last)) {
            lastMark.put(key, mark.eventTime());
        }
        if (!gap && last != null && minuteOf(mark.eventTime()).isAfter(minuteOf(last)) && watchesMarks(key)) {
            advanceMarkWatermark(key, minuteOf(mark.eventTime()).minus(MINUTE));
        }
    }

    /** A replayed mark candle of a pair catching up: its path run as marks, then its minute is whole. */
    private void onMarkCandle(BookKey key, MinuteKline kline) {
        if (!markCatchUps.containsKey(key)) {
            return;
        }
        markPath(kline).forEach(mark -> onMark(key, mark, FillSource.REPLAY));
        advanceMarkWatermark(key, kline.openTime());
    }

    /**
     * The marks a candle stands for, in the order plan 9.5 assumes: open, the extreme nearer the open, the other
     * extreme, close; at times inside the minute, so a conditional order placed during it is reached only by what
     * follows its placement (D-77). Without an open, the low comes first.
     */
    static List<MarkPrice> markPath(MinuteKline kline) {
        BigDecimal open = kline.open() != null ? kline.open() : kline.low();
        boolean lowFirst = open.subtract(kline.low())
                        .abs()
                        .compareTo(kline.high().subtract(open).abs())
                <= 0;
        Instant start = kline.openTime();
        return List.of(
                new MarkPrice(kline.pairId(), open, start),
                new MarkPrice(kline.pairId(), lowFirst ? kline.low() : kline.high(), start.plusSeconds(20)),
                new MarkPrice(kline.pairId(), lowFirst ? kline.high() : kline.low(), start.plusSeconds(40)),
                new MarkPrice(kline.pairId(), kline.close(), start.plus(MINUTE).minusMillis(1)));
    }

    /**
     * Ends a pair's catch-up once its replay reached past the minute of the first held mark and two live marks followed
     * it; once that minute is over, a held mark asks for the next candles, at most once per {@code replay.retry-delay},
     * so a candle the exchange has not published yet is never polled faster than the marks come. The held marks inside
     * replayed candles are dropped.
     */
    private void finishCatchUp(BookKey key, MarkCatchUp catchUp, boolean onMark) {
        if (catchUp.replaying) {
            return;
        }
        catchUp.held.removeIf(mark -> mark.eventTime().isBefore(catchUp.replayedUntil));
        if (catchUp.firstHeld == null) {
            return;
        }
        if (!catchUp.replayedUntil.isAfter(minuteOf(catchUp.firstHeld))) {
            // The minute of the first live mark is not replayed yet: its candle comes once it closes (plan 9.5 rule 5),
            // asked for again at the next held mark once retry-delay has passed, as the exchange may lag the close.
            Instant now = clock.instant();
            if (onMark && !now.isBefore(catchUp.replayedUntil.plus(MINUTE)) && !now.isBefore(catchUp.nextAskAt)) {
                catchUp.replaying = true;
                catchUp.nextAskAt = now.plus(properties.replay().retryDelay());
                replays.offer(new Replay(key, catchUp.replayedUntil, true));
            }
            return;
        }
        if (catchUp.held.size() < 2) {
            return;
        }
        markCatchUps.remove(key);
        catchUp.held.forEach(mark -> onMark(key, mark, FillSource.LIVE));
        Instant last = catchUp.held.getLast().eventTime();
        lastMark.put(key, last);
        advanceMarkWatermark(key, minuteOf(last).minus(MINUTE));
        catchingUp(key, false);
        log.info("TR-42 FUTURES {}: marks caught up to {}; live again", key.pairId(), last);
    }

    /** Starts a pair's catch-up: it is {@code MARK_STALE} meanwhile, and its mark candles from {@code from} replay. */
    private MarkCatchUp startCatchUp(BookKey key, Instant from) {
        MarkCatchUp catchUp = new MarkCatchUp(from);
        markCatchUps.put(key, catchUp);
        catchingUp(key, true);
        replays.offer(new Replay(key, from, true));
        return catchUp;
    }

    private void catchingUp(BookKey key, boolean catchingUp) {
        try {
            matching.markCatchingUp(key.pairId(), catchingUp);
        } catch (RuntimeException failure) {
            log.warn("TR-42 FUTURES {} catch-up not reported: {}", key.pairId(), failure.toString());
        }
    }

    /** Whether a mark of the pair can trigger or liquidate anything. */
    private boolean watchesMarks(BookKey key) {
        return markTriggers.hasOrders(key.market(), key.pairId()) || liquidations.hasLevels(key.pairId());
    }

    private void advanceMarkWatermark(BookKey key, Instant openTime) {
        try {
            matching.advanceMarkWatermark(key.pairId(), openTime);
        } catch (RuntimeException failure) {
            log.warn(
                    "TR-42 FUTURES {} mark watermark not advanced to {}: {}",
                    key.pairId(),
                    openTime,
                    failure.toString());
        }
    }

    /** Reads the Trader's liquidation levels on the pair again, as the database holds them now. */
    private void refreshLevels(UUID userId, UUID pairId) {
        try {
            liquidations.replace(userId, pairId, matching.liquidationLevels(userId, pairId));
        } catch (RuntimeException failure) {
            log.warn("TR-22 liquidation levels of user {} on {} not read: {}", userId, pairId, failure.toString());
        }
    }

    /** Triggers the conditional orders a mark price reached, earliest placed first (TR-20). */
    private void triggerOnMark(BookKey key, MarkPrice mark, FillSource source) {
        PriceRange range = PriceRange.ofPrints(
                key.market(),
                key.pairId(),
                List.of(new PriceRange.Print(mark.price(), BigDecimal.ZERO, false, mark.eventTime())));
        for (TriggerBook.Triggered fired : markTriggers.onRange(range)) {
            try {
                Instant at = source == FillSource.REPLAY ? clock.instant() : fired.at();
                matching.trigger(fired.order(), at, fired.stopPrice(), source, null);
            } catch (RuntimeException failure) {
                markTriggers.track(fired.order());
                log.warn(
                        "TR-20 paper order {} trigger failed; it waits for the next mark: {}",
                        fired.order().orderId(),
                        failure.toString());
            }
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
                        .map(reached ->
                                new Fill(reached.order(), reached.at(), range.source(), ReplayedCandle.of(range), null))
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
            replays.offer(new Replay(key, last.plus(MINUTE), false));
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
                        .map(pending -> new Fill(
                                pending.order(), pending.tradedAt(), pending.source(), pending.candle(), pending))
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
                .computeIfAbsent(fill.order().userId(), ignored -> new ArrayList<>())
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
                if (!matching.fill(fill.order(), fill.tradedAt(), fill.source(), fill.candle())) {
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
                        failure.fill().candle(),
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
        replays.offer(new Replay(key, from, false));
    }

    /**
     * Starts the catch-up of a Futures pair with marks to watch at start (TR-42): from the later of the candle after
     * its mark watermark and the minute {@code since}, at most {@code replay.max-window} back.
     */
    private void startupMarkReplay(BookKey key, Instant since) {
        Instant from = minuteOf(since);
        Instant afterWatermark = matching.markWatermark(key.pairId())
                .map(watermark -> watermark.plus(MINUTE))
                .orElse(from);
        if (afterWatermark.isAfter(from)) {
            from = afterWatermark;
        }
        Instant oldest = minuteOf(clock.instant()).minus(properties.replay().maxWindow());
        if (from.isBefore(oldest)) {
            log.warn(
                    "TR-42 FUTURES {}: mark candles from {} to {} are beyond the replay window of {} and not replayed",
                    key.pairId(),
                    from,
                    oldest,
                    properties.replay().maxWindow());
            from = oldest;
        }
        startCatchUp(key, from);
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
            Optional<List<MinuteKline>> page = page(replay, cursor);
            if (page.isEmpty()) {
                continue;
            }
            if (!replay.mark()) {
                prefetchQuoteRates(key, page.get(), cursor);
            }
            boolean advanced = false;
            for (MinuteKline kline : page.get()) {
                if (!kline.openTime().isBefore(cursor)) {
                    queue.offer(
                            replay.mark()
                                    ? new Command.MarkCandle(key, kline)
                                    : new Command.Range(new PriceRange(
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
        queue.offer(replay.mark() ? new Command.MarkReplayDone(key, cursor) : new Command.ReplayDone(key, cursor));
        log.info(
                "TR-02 {} {} paper {}replay done up to {}",
                key.market(),
                key.pairId(),
                replay.mark() ? "mark " : "",
                cursor);
    }

    /** The next page of closed candles from {@code cursor}; empty after waiting when it could not be fetched now. */
    private Optional<List<MinuteKline>> page(Replay replay, Instant cursor) throws InterruptedException {
        BookKey key = replay.key();
        MinuteKlineBatch batch;
        try {
            batch = replay.mark()
                    ? market.closedMarkPriceKlines(key.pairId(), cursor)
                    : market.closedMinuteKlines(key.market(), key.pairId(), cursor);
        } catch (RuntimeException failure) {
            log.warn(
                    "TR-02 {} {} paper replay failed at {}; retried in {}: {}",
                    key.market(),
                    key.pairId(),
                    cursor,
                    properties.replay().retryDelay(),
                    failure.toString());
            Thread.sleep(properties.replay().retryDelay());
            return Optional.empty();
        }
        if (batch.refused()) {
            Duration wait = Duration.between(clock.instant(), batch.retryAt());
            Thread.sleep(wait.isPositive() ? wait : properties.replay().retryDelay());
            return Optional.empty();
        }
        return Optional.of(batch.klines());
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

    /**
     * A fill to store; {@code candle} is the replayed candle that decided it, or {@code null} when live, and
     * {@code previous} the retry it comes from, or {@code null} for a first try.
     */
    private record Fill(
            RestingOrder order, Instant tradedAt, FillSource source, ReplayedCandle candle, PendingFill previous) {}

    private record Failure(Fill fill, RuntimeException cause) {}

    /** A pair's replay from the candle opened at {@code from}: of its last price candles, or its mark candles. */
    private record Replay(BookKey key, Instant from, boolean mark) {}

    /**
     * A Futures pair catching up on its marks (TR-42): the live marks held meanwhile, the first of them, and how far
     * the replay reached.
     */
    private static final class MarkCatchUp {

        private final List<MarkPrice> held = new ArrayList<>();
        private Instant firstHeld;
        private Instant replayedUntil;
        private boolean replaying = true;
        private Instant nextAskAt = Instant.MIN;

        private MarkCatchUp(Instant from) {
            this.replayedUntil = from;
        }

        private void hold(MarkPrice mark) {
            if (firstHeld == null) {
                firstHeld = mark.eventTime();
            }
            held.add(mark);
        }

        private void replayed(Instant until) {
            replaying = false;
            if (until.isAfter(replayedUntil)) {
                replayedUntil = until;
            }
        }
    }

    /** A fill to try again, with when it first failed and when to try next. */
    private record PendingFill(
            RestingOrder order,
            Instant tradedAt,
            FillSource source,
            ReplayedCandle candle,
            Instant firstFailedAt,
            Instant nextTryAt) {}

    /** What the consumer is asked to do. */
    private sealed interface Command {

        record Track(RestingOrder order) implements Command {}

        record TrackTrigger(TriggerOrder order) implements Command {}

        /** A Futures conditional order on the mark price starts waiting (TR-20). */
        record TrackMarkTrigger(TriggerOrder order) implements Command {}

        /** Read the pair's waiting mark prices (TR-20). */
        record Mark(BookKey key) implements Command {}

        /** A replayed 1-minute mark candle of a pair catching up (TR-42). */
        record MarkCandle(BookKey key, MinuteKline kline) implements Command {}

        /** A mark replay has caught up; {@code until} is the open time of the first mark candle not replayed. */
        record MarkReplayDone(BookKey key, Instant until) implements Command {}

        /** Close the Trader's cross position on the pair if the liquidation still calls for it (TR-23). */
        record ForceClose(UUID userId, UUID pairId) implements Command {}

        /** Resume the cross liquidations that started before {@code startedBefore} and still run (TR-23). */
        record ResumeLiquidations(Instant startedBefore) implements Command {}

        /** Read a Trader's liquidation levels on a pair again (TR-22). */
        record AccountChanged(UUID userId, UUID pairId) implements Command {}

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
