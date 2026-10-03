package com.cryptopilot.trading.job;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.service.MatchingService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the matching engine with the {@code kline_1m} updates of the market streams, and replays from closed 1-minute
 * candles whatever it missed, at start-up or when the live feed skips a minute, through the same
 * {@link MatchingWorker#submit} path.
 *
 * <ul>
 *   <li>An update becomes a {@link PriceRange}: its candle's low and high so far, from the candle's open time to the
 *       update's time, closed or not. A fill is recorded at the candle's open time (D-78), so the many live updates of
 *       a minute and the one closed candle of a replay fill the same plans at the same time.
 *   <li>At start, every pair with an ACTIVE LIMIT entry is replayed from the later of the candle after its watermark
 *       and the minute of its first activation, at most {@code replay.max-window} back (A-04, Q-34). Older candles are
 *       skipped with a warning: nothing is ever filled from a guessed price.
 *   <li>While running, a pair whose live updates jump past the minute after its last closed candle (a stream drop, a
 *       reconnection, a closed candle that never came) goes back to replaying from that minute. Nothing after the
 *       hole reaches the engine before the hole is replayed, so the watermark never passes it.
 *   <li>While a pair replays, its live updates are held, one merged range per minute. It goes live once the replay
 *       has reached the held minutes, or, with nothing held, the current minute; minutes already replayed are dropped,
 *       so no candle is applied twice.
 * </ul>
 *
 * <p>Rule: NSF-07, BR-33; TECHNICAL_DESIGN 7.7; A-04; D-09, D-77, D-78; ADR-011.
 *
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (event time, not
 * processing time; a watermark never passes event time whose input is not complete).
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11 (rebuilding
 * state by replaying a stream from a recorded offset gives the same result as processing it live).
 */
@Component
public class MinuteKlineFeed implements MinuteKlineListener {

    private static final Logger log = LoggerFactory.getLogger(MinuteKlineFeed.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final MatchingWorker worker;
    private final MatchingService matching;
    private final MarketApi market;
    private final MatchingProperties properties;
    private final Clock clock;
    private final ThreadFactory threads;

    private final Map<PairKey, PairFeed> feeds = new ConcurrentHashMap<>();
    private final BlockingQueue<Replay> replays = new LinkedBlockingQueue<>();

    /** Replays queued or running. */
    private final AtomicInteger outstanding = new AtomicInteger();

    /** Whether a pair seen for the first time goes live at once; until the first start, every pair waits. */
    private volatile boolean started;

    private volatile boolean stopped;

    /** Guarded by {@code this}. */
    private Thread replayer;

    @Autowired
    public MinuteKlineFeed(
            MatchingWorker worker,
            MatchingService matching,
            MarketApi market,
            MatchingProperties properties,
            Clock clock) {
        this(worker, matching, market, properties, clock, Thread.ofVirtual().factory());
    }

    MinuteKlineFeed(
            MatchingWorker worker,
            MatchingService matching,
            MarketApi market,
            MatchingProperties properties,
            Clock clock,
            ThreadFactory threads) {
        this.worker = worker;
        this.matching = matching;
        this.market = market;
        this.properties = properties;
        this.clock = clock;
        this.threads = threads;
    }

    @Override
    public void onMinuteKline(MinuteKline kline) {
        if (!properties.enabled()) {
            return;
        }
        PairKey key = new PairKey(kline.market(), kline.pairId());
        feeds.computeIfAbsent(key, PairFeed::new).accept(rangeOf(kline));
    }

    /**
     * Starts the matching engine, then replays the pairs with ACTIVE LIMIT entries on a replay thread while the other
     * pairs go live. A second call does nothing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.enabled() || started) {
            return;
        }
        worker.start();
        if (!worker.isRunning()) {
            log.error("NSF-07 1m candles are not fed: the matching engine did not start");
            return;
        }
        stopped = false;
        List<Replay> startup = properties.replay().enabled() ? startupReplays() : List.of();
        // Marked before the other pairs go live, so a replayed pair holds its live updates from here on.
        startup.forEach(
                replay -> feeds.computeIfAbsent(replay.key(), PairFeed::new).replayFrom(replay.from()));
        started = true;
        feeds.values().forEach(PairFeed::goLiveIfWaiting);
        startup.forEach(this::enqueue);
        Thread thread = threads.newThread(this::runReplays);
        thread.setName("trading-kline-replay");
        thread.start();
        replayer = thread;
        log.info("NSF-07 1m candles feed started; {} pairs to replay", startup.size());
    }

    /** Stops the replays; the pairs wait again until the next start. */
    @PreDestroy
    public synchronized void stop() {
        started = false;
        stopped = true;
        if (replayer != null) {
            replayer.interrupt();
            try {
                replayer.join(properties.stopTimeout());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            replayer = null;
        }
        replays.clear();
        outstanding.set(0);
        feeds.clear();
    }

    /** Whether no replay is queued or running. */
    public boolean isIdle() {
        return outstanding.get() == 0;
    }

    /** Where each pair with an ACTIVE LIMIT entry starts its replay. */
    private List<Replay> startupReplays() {
        Map<PairKey, Instant> firstActivation = matching.activeEntries().stream()
                .collect(Collectors.toMap(
                        entry -> new PairKey(entry.market(), entry.pairId()),
                        TrackedEntry::activatedAt,
                        (a, b) -> a.isBefore(b) ? a : b));
        Instant oldest = minuteOf(clock.instant()).minus(properties.replay().maxWindow());
        List<Replay> startup = new ArrayList<>();
        firstActivation.forEach((key, activated) -> {
            Instant from = minuteOf(activated);
            Instant afterWatermark = matching.watermark(key.market(), key.pairId())
                    .map(watermark -> watermark.plus(MINUTE))
                    .orElse(from);
            if (afterWatermark.isAfter(from)) {
                from = afterWatermark;
            }
            if (from.isBefore(oldest)) {
                log.warn(
                        "NSF-07 {} {}: candles from {} to {} are beyond the replay window of {} and not replayed",
                        key.market(),
                        key.pairId(),
                        from,
                        oldest,
                        properties.replay().maxWindow());
                from = oldest;
            }
            startup.add(new Replay(key, from));
        });
        return startup;
    }

    private void enqueue(Replay replay) {
        outstanding.incrementAndGet();
        replays.add(replay);
    }

    /** The replay thread: one pair at a time, until stopped. */
    private void runReplays() {
        while (!stopped) {
            Replay replay;
            try {
                replay = replays.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                replay(replay);
            } finally {
                outstanding.decrementAndGet();
            }
        }
    }

    /** Replays one pair's closed candles page by page, then lets its held live updates through. */
    private void replay(Replay replay) {
        PairFeed feed = feeds.get(replay.key());
        if (feed == null) {
            return;
        }
        Instant cursor = replay.from();
        int catchUps = 0;
        try {
            while (!stopped) {
                MinuteKlineBatch batch = market.closedMinuteKlines(
                        replay.key().market(), replay.key().pairId(), cursor);
                if (batch.refused()) {
                    sleepUntil(batch.retryAt());
                    continue;
                }
                boolean advanced = false;
                for (MinuteKline kline : batch.klines()) {
                    if (!kline.openTime().isBefore(cursor)) {
                        worker.submit(rangeOf(kline));
                        cursor = kline.openTime().plus(MINUTE);
                        advanced = true;
                    }
                }
                if (advanced) {
                    continue;
                }
                if (feed.goLiveAfterReplay(
                        cursor, catchUps >= properties.replay().catchUpAttempts())) {
                    return;
                }
                catchUps++;
                Thread.sleep(properties.replay().catchUpWait());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            log.error(
                    "NSF-07 {} {} replay failed at {}",
                    replay.key().market(),
                    replay.key().pairId(),
                    cursor,
                    failure);
            feed.goLiveAfterReplay(cursor, true);
        }
    }

    private void sleepUntil(Instant retryAt) throws InterruptedException {
        Duration wait = Duration.between(clock.instant(), retryAt);
        if (wait.isPositive()) {
            Thread.sleep(wait);
        }
    }

    private static PriceRange rangeOf(MinuteKline kline) {
        Instant at = kline.eventTime().isBefore(kline.openTime()) ? kline.openTime() : kline.eventTime();
        return new PriceRange(
                kline.market(), kline.pairId(), kline.low(), kline.high(), kline.openTime(), at, kline.closed());
    }

    private static Instant minuteOf(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MINUTES);
    }

    /** One pair's updates: held while it waits or replays, handed to the engine in order once live. */
    private final class PairFeed {

        private final PairKey key;
        private State state = State.WAITING;

        /** Held updates, one merged range per candle, by open time. */
        private final TreeMap<Instant, PriceRange> held = new TreeMap<>();

        /** Live candles that opened before this were replayed and are not applied again. */
        private Instant replayedUntil;

        /** The open time of the last closed candle handed to the engine; the next live one must follow it. */
        private Instant lastClosed;

        PairFeed(PairKey key) {
            this.key = key;
        }

        synchronized void accept(PriceRange range) {
            if (state == State.WAITING && started) {
                goLive();
            }
            if (state == State.LIVE) {
                live(range);
            } else {
                hold(range);
            }
        }

        synchronized void replayFrom(Instant from) {
            state = State.REPLAYING;
            lastClosed = from.minus(MINUTE);
        }

        synchronized void goLiveIfWaiting() {
            if (state == State.WAITING) {
                goLive();
            }
        }

        /**
         * Goes live once the replay has reached the held updates, or, with nothing held, the current minute. Held
         * updates of candles already replayed are dropped, the rest handed on in order.
         *
         * @param replayedUntil the open time of the first candle not replayed
         * @param evenWithAHole whether to go live although candles are missing between the replay and what follows
         * @return whether the pair is live now
         */
        synchronized boolean goLiveAfterReplay(Instant replayedUntil, boolean evenWithAHole) {
            boolean ready = held.isEmpty()
                    ? !replayedUntil.isBefore(minuteOf(clock.instant()))
                    : !held.firstKey().isAfter(replayedUntil);
            if (!ready && !evenWithAHole) {
                return false;
            }
            this.replayedUntil = replayedUntil;
            lastClosed = replayedUntil.minus(MINUTE);
            if (!ready) {
                Instant next = held.isEmpty() ? minuteOf(clock.instant()) : held.firstKey();
                log.warn(
                        "NSF-07 {} {}: no candles from {} to {}; not matched",
                        key.market(),
                        key.pairId(),
                        replayedUntil,
                        next);
                // The exchange has nothing for the hole: accept it rather than replay it again.
                lastClosed = next.minus(MINUTE);
            }
            held.headMap(replayedUntil).clear();
            goLive();
            return true;
        }

        private void goLive() {
            state = State.LIVE;
            while (state == State.LIVE && !held.isEmpty()) {
                live(held.pollFirstEntry().getValue());
            }
        }

        /** Hands a live range on, unless it jumps past the minute after the last closed candle. */
        private void live(PriceRange range) {
            if (lastClosed != null && range.from().isAfter(lastClosed.plus(MINUTE))) {
                Instant from = lastClosed.plus(MINUTE);
                if (properties.replay().enabled()) {
                    log.warn(
                            "NSF-07 {} {}: candles from {} to {} missing from the stream; replaying them",
                            key.market(),
                            key.pairId(),
                            from,
                            range.from());
                    state = State.REPLAYING;
                    hold(range);
                    enqueue(new Replay(key, from));
                    return;
                }
                log.warn(
                        "NSF-07 {} {}: no candles from {} to {}; not matched",
                        key.market(),
                        key.pairId(),
                        from,
                        range.from());
                lastClosed = range.from().minus(MINUTE);
            }
            hand(range);
        }

        private void hold(PriceRange range) {
            held.merge(range.from(), range, PriceRange::mergedWith);
            if (held.size() > properties.replay().maxBufferedMinutes()) {
                PriceRange dropped = held.pollFirstEntry().getValue();
                log.warn("NSF-07 {} {}: held candle {} dropped", dropped.market(), dropped.pairId(), dropped.from());
            }
        }

        private void hand(PriceRange range) {
            if (replayedUntil != null && range.from().isBefore(replayedUntil)) {
                return;
            }
            if (lastClosed == null) {
                lastClosed = range.from().minus(MINUTE);
            }
            if (range.closed() && range.from().isAfter(lastClosed)) {
                lastClosed = range.from();
            }
            worker.submit(range);
        }
    }

    private enum State {
        /** Before the first start: updates are held. */
        WAITING,
        /** Closed candles are being replayed: live updates are held. */
        REPLAYING,
        /** Updates go to the engine as they come. */
        LIVE
    }

    private record PairKey(MarketType market, UUID pairId) {}

    private record Replay(PairKey key, Instant from) {}
}
