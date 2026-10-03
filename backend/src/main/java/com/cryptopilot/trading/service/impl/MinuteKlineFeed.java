package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.job.MatchingWorker;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the matching engine with the {@code kline_1m} updates of the market streams, and after a restart replays the
 * closed 1-minute candles it missed, through the same {@link MatchingWorker#submit} path.
 *
 * <ul>
 *   <li>An update becomes a {@link PriceRange}: its candle's low and high so far, from the candle's open time to the
 *       update's time, closed or not. A fill is recorded at the candle's open time (D-78), so the many live updates of
 *       a minute and the one closed candle of a replay fill the same plans at the same time.
 *   <li>At start, every pair with an ACTIVE LIMIT entry is replayed from the candle after its watermark (the last
 *       candle closed and fully matched), or from the minute of its first activation when it has none, at most
 *       {@code replay.max-window} back (A-04, Q-34). Candles older than that are skipped with a warning: nothing is
 *       ever filled from a guessed price.
 *   <li>While a pair replays, its live updates are held, one merged range per minute; once the replay has reached the
 *       held minutes, those it already replayed are dropped, so no candle is applied twice, and the rest are handed on
 *       in order. A hole between the last replayed and the first held minute is fetched again a few times (the
 *       candle may close during the replay), then logged.
 * </ul>
 *
 * <p>Rule: NSF-07, BR-33; TECHNICAL_DESIGN 7.7; A-04; D-09, D-77, D-78; ADR-011.
 *
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792–1803 (event time, not
 * processing time; a watermark marks the event time up to which input is complete).
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
        feeds.computeIfAbsent(new PairKey(kline.market(), kline.pairId()), ignored -> new PairFeed())
                .accept(rangeOf(kline));
    }

    /**
     * Starts the matching engine, then replays the pairs with ACTIVE LIMIT entries on a thread of its own while the
     * other pairs go live. A second call does nothing.
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
        List<Replay> replays = properties.replay().enabled() ? replays() : List.of();
        // Marked before the other pairs go live, so a replayed pair holds its live updates from here on.
        replays.forEach(replay ->
                feeds.computeIfAbsent(replay.key(), ignored -> new PairFeed()).replayFrom(replay.from()));
        started = true;
        feeds.values().forEach(PairFeed::goLiveIfWaiting);
        Thread thread = threads.newThread(() -> replays.forEach(this::replay));
        thread.setName("trading-kline-replay");
        thread.start();
        replayer = thread;
        log.info("NSF-07 1m candles feed started; {} pairs to replay", replays.size());
    }

    /** Stops the replay; the pairs wait again until the next start. */
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
        feeds.clear();
    }

    /** Where each pair with an ACTIVE LIMIT entry starts its replay. */
    private List<Replay> replays() {
        Map<PairKey, Instant> firstActivation = matching.activeEntries().stream()
                .collect(Collectors.toMap(
                        entry -> new PairKey(entry.market(), entry.pairId()),
                        TrackedEntry::activatedAt,
                        (a, b) -> a.isBefore(b) ? a : b));
        Instant oldest = minuteOf(clock.instant()).minus(properties.replay().maxWindow());
        List<Replay> replays = new ArrayList<>();
        firstActivation.forEach((key, activated) -> {
            Instant from = matching.watermark(key.market(), key.pairId())
                    .map(watermark -> watermark.plus(MINUTE))
                    .orElseGet(() -> minuteOf(activated));
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
            replays.add(new Replay(key, from));
        });
        return replays;
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

        private State state = State.WAITING;

        /** Held updates, one merged range per candle, by open time. */
        private final TreeMap<Instant, PriceRange> held = new TreeMap<>();

        /** Live candles that opened before this were replayed and are not applied again. */
        private Instant replayedUntil;

        /** The open time of the latest candle handed to the engine. */
        private Instant lastOpen;

        synchronized void accept(PriceRange range) {
            if (state == State.WAITING && started) {
                goLive();
            }
            if (state == State.LIVE) {
                hand(range);
            } else {
                hold(range);
            }
        }

        synchronized void replayFrom(Instant from) {
            state = State.REPLAYING;
            lastOpen = from.minus(MINUTE);
        }

        synchronized void goLiveIfWaiting() {
            if (state == State.WAITING) {
                goLive();
            }
        }

        /**
         * Goes live once the replay has reached the held updates: those of candles already replayed are dropped, the
         * rest handed on in order.
         *
         * @param replayedUntil the open time of the first candle not replayed
         * @param evenWithAHole whether to go live although candles are missing between the replay and the held updates
         * @return whether the pair is live now
         */
        synchronized boolean goLiveAfterReplay(Instant replayedUntil, boolean evenWithAHole) {
            boolean hole = !held.isEmpty() && held.firstKey().isAfter(replayedUntil);
            if (hole && !evenWithAHole) {
                return false;
            }
            if (hole) {
                log.warn(
                        "NSF-07 {}: no candles from {} to {}; not matched",
                        held.firstEntry().getValue().pairId(),
                        replayedUntil,
                        held.firstKey());
            }
            this.replayedUntil = replayedUntil;
            lastOpen = replayedUntil.minus(MINUTE);
            held.headMap(replayedUntil).clear();
            goLive();
            return true;
        }

        private void goLive() {
            state = State.LIVE;
            held.values().forEach(this::hand);
            held.clear();
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
            if (lastOpen != null && range.from().isAfter(lastOpen.plus(MINUTE))) {
                log.warn(
                        "NSF-07 {} {}: no candles from {} to {}; not matched",
                        range.market(),
                        range.pairId(),
                        lastOpen.plus(MINUTE),
                        range.from());
            }
            if (lastOpen == null || range.from().isAfter(lastOpen)) {
                lastOpen = range.from();
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
