package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.service.BackfillRun;
import com.cryptopilot.market.service.CandleBackfillService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
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
 * <p>Rule: NSF-02; TECHNICAL_DESIGN 7.1.2 (the caller contract) and 10; D-41, D-42.
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
        log.info(
                "NSF-02 scheduled on '{}' ({}), and after each symbol synchronisation",
                properties.cron(),
                properties.zone());
    }

    /** A market's statuses are current: backfill it now, on the scheduler rather than on the sync's thread. */
    @EventListener
    public void onSymbolsSynchronised(SymbolsSynchronised event) {
        if (properties.enabled()) {
            continueAt(event.market(), clock.instant());
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
