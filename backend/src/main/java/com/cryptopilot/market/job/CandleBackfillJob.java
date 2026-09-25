package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.model.BackfillRun;
import com.cryptopilot.market.model.GapFill;
import com.cryptopilot.market.service.CandleBackfillService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs NSF-02: a market's backfill after each successful symbol synchronisation of that market — so the first
 * run at start-up works from statuses the exchange has just confirmed — and then hourly, one minute past the
 * hour, when only the candles that closed since need fetching.
 *
 * <h2>Triggered by the stream (NSF-03)</h2>
 *
 * <ul>
 *   <li>{@link GapDetected} — the gap is queued for its market and a run is started now. A run fills the queued
 *       gaps first, then brings every series up to date. A gap the weight share or a refusal interrupts stays
 *       queued, shortened to what is left.
 *   <li>{@link MarketStreamReconnected} — a run of that market is started now, so the candles that closed while
 *       the connection was down are stored within minutes (SRS 4.2: within 10 minutes of reconnection).
 * </ul>
 *
 * <h2>Outcomes</h2>
 *
 * <ul>
 *   <li>Finished — nothing more until the next run.
 *   <li>Paused at the weight share — the run is continued at the instant the service reports, the next minute.
 *   <li>{@code RATE_LIMITED}, {@code BANNED}, {@code CIRCUIT_OPEN} — stopped; continued at {@code retryAt}.
 *   <li>{@code UNAVAILABLE}, or anything unexpected — stopped; the next scheduled run continues.
 *   <li>{@code REJECTED}, {@code MALFORMED} — handled inside the service, per series.
 * </ul>
 *
 * <p>One continuation is pending per market at most; a newer one replaces it. Continuing is always a task
 * handed to the {@link TaskScheduler}; the thread is never held. One instance, no distributed lock (D-39).
 *
 * <p>Queued gaps live in memory. One lost with a restart is not lost for good: at start-up the stored series of
 * the recent window ({@code gapScanWindow}, 7 days) are scanned for holes, and each is queued again; a hole behind
 * the latest candle is also reported by the stream's first closed candle of the series.
 *
 * <p>Rule: NSF-02, NSF-03; TECHNICAL_DESIGN 7.1.2 (the caller contract), 7.1 step 4, and 10; D-41, D-42; A-33.
 */
@Component
public class CandleBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(CandleBackfillJob.class);

    /** What one run of one market came to. */
    public enum Outcome {

        /** Every series is current. */
        COMPLETED,

        /** Stopped at the weight share, or refused with a known instant; continued then. */
        CONTINUATION_SCHEDULED,

        /** Stopped by an outage or an unexpected failure; the next scheduled run continues. */
        WAIT_FOR_NEXT_RUN
    }

    private final CandleBackfillService backfill;
    private final TaskScheduler scheduler;
    private final CandleBackfillProperties properties;
    private final Clock clock;
    private final Map<MarketType, ScheduledFuture<?>> pending = new EnumMap<>(MarketType.class);
    private final Map<MarketType, Deque<GapDetected>> gaps = new EnumMap<>(MarketType.class);
    private final ReentrantLock running = new ReentrantLock();

    public CandleBackfillJob(
            CandleBackfillService backfill, TaskScheduler scheduler, CandleBackfillProperties properties, Clock clock) {
        this.backfill = backfill;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /** The hourly catch-up, when enabled. The start-up run follows the first symbol synchronisation instead. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-02 candle backfill is disabled");
            return;
        }
        scheduler.schedule(this::runAll, new CronTrigger(properties.cron(), properties.zone()));
        scanForStoredGaps();
        log.info(
                "NSF-02 scheduled on '{}' ({}), and after each symbol synchronisation",
                properties.cron(),
                properties.zone());
    }

    /**
     * Queues the holes found inside the stored series of the recent window and runs each affected market now, so
     * a gap reported before a restart — the queue lives in memory — is found again and filled (A-33).
     *
     * @return how many gaps were queued
     */
    public int scanForStoredGaps() {
        try {
            List<GapDetected> found = backfill.storedGaps();
            found.forEach(this::onGapDetected);
            log.info("NSF-02 start-up scan: {} holes inside the stored series queued", found.size());
            return found.size();
        } catch (RuntimeException failure) {
            log.error("NSF-02 start-up gap scan failed; the stream and the hourly runs still find new gaps", failure);
            return 0;
        }
    }

    /** A market's statuses are current: backfill it now, on the scheduler rather than on the sync's thread. */
    @EventListener
    public void onSymbolsSynchronised(SymbolsSynchronised event) {
        if (properties.enabled()) {
            continueAt(event.market(), clock.instant());
        }
    }

    /** The stream found candles missing: queue the gap and run its market now. */
    @EventListener
    public void onGapDetected(GapDetected gap) {
        if (!properties.enabled()) {
            return;
        }
        synchronized (gaps) {
            gaps.computeIfAbsent(gap.market(), market -> new ArrayDeque<>()).addLast(gap);
        }
        continueAt(gap.market(), clock.instant());
    }

    /** A stream connection came back after a loss: bring the market up to date now. */
    @EventListener
    public void onStreamReconnected(MarketStreamReconnected event) {
        if (properties.enabled()) {
            continueAt(event.market(), clock.instant());
        }
    }

    /** The gaps waiting to be filled for a market, oldest first. */
    public List<GapDetected> queuedGaps(MarketType market) {
        synchronized (gaps) {
            return List.copyOf(gaps.getOrDefault(market, new ArrayDeque<>()));
        }
    }

    /** One run of both markets, each on its own. */
    public void runAll() {
        for (MarketType market : MarketType.values()) {
            run(market);
        }
    }

    /** One run of one market, and what it came to. */
    public Outcome run(MarketType market) {
        running.lock();
        try {
            Optional<Instant> gapPause = fillGaps(market);
            if (gapPause.isPresent()) {
                continueAt(market, gapPause.get());
                return Outcome.CONTINUATION_SCHEDULED;
            }
            BackfillRun result = backfill.backfill(market);
            if (result.paused()) {
                continueAt(market, result.pausedUntil().orElseThrow());
                return Outcome.CONTINUATION_SCHEDULED;
            }
            log.info(
                    "NSF-02 {} complete: {} series, {} rows, defects {}",
                    market,
                    result.seriesCompleted(),
                    result.rowsInserted(),
                    result.defects());
            return Outcome.COMPLETED;
        } catch (BinanceClientException refusal) {
            if (refusal.retryAt().isPresent()) {
                continueAt(market, refusal.retryAt().get());
                log.warn(
                        "NSF-02 {} refused ({}); continuing at {}",
                        market,
                        refusal.kind(),
                        refusal.retryAt().get());
                return Outcome.CONTINUATION_SCHEDULED;
            }
            log.warn("NSF-02 {} stopped ({}); the next scheduled run continues", market, refusal.kind(), refusal);
            return Outcome.WAIT_FOR_NEXT_RUN;
        } catch (RuntimeException unexpected) {
            log.error("NSF-02 {} failed unexpectedly; the next scheduled run continues", market, unexpected);
            return Outcome.WAIT_FOR_NEXT_RUN;
        } finally {
            running.unlock();
        }
    }

    /** Fills the market's queued gaps in order; the instant to continue at when the weight share stopped one. */
    private Optional<Instant> fillGaps(MarketType market) {
        while (true) {
            GapDetected gap;
            synchronized (gaps) {
                gap = gaps.getOrDefault(market, new ArrayDeque<>()).peekFirst();
            }
            if (gap == null) {
                return Optional.empty();
            }
            GapFill fill = backfill.fillGap(gap);
            synchronized (gaps) {
                Deque<GapDetected> queue = gaps.get(market);
                queue.removeFirst();
                fill.remaining().ifPresent(queue::addFirst);
            }
            if (fill.pausedUntil().isPresent()) {
                return fill.pausedUntil();
            }
            log.info(
                    "NSF-02 {} gap {} {} {} -> {} filled, {} rows",
                    market,
                    gap.symbol(),
                    gap.timeframe(),
                    gap.from(),
                    gap.to(),
                    fill.rowsInserted());
        }
    }

    private void continueAt(MarketType market, Instant at) {
        synchronized (pending) {
            ScheduledFuture<?> previous = pending.get(market);
            if (previous != null) {
                previous.cancel(false);
            }
            pending.put(market, scheduler.schedule(() -> run(market), at));
        }
    }
}
