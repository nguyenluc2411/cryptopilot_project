package com.cryptopilot.market.job;

import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.config.FuturesMetricsProperties;
import com.cryptopilot.market.service.FuturesMetricsService;
import com.cryptopilot.market.service.MetricsRun;
import com.cryptopilot.market.service.SettlementRun;
import java.time.Instant;
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
 * Runs NSF-04 every five minutes, 90 seconds after each 5-minute instant: first the funding settlements, then
 * the open interest and long/short account ratio.
 *
 * <h2>Outcomes</h2>
 *
 * <ul>
 *   <li>Completed — nothing more until the next run. A metrics collection that stopped at its request limit is
 *       also complete: the next run continues from what was stored.
 *   <li>{@code RATE_LIMITED}, {@code BANNED}, {@code CIRCUIT_OPEN} — stopped; run again at {@code retryAt}.
 *   <li>{@code UNAVAILABLE}, or anything unexpected — stopped; the next scheduled run continues.
 *   <li>{@code REJECTED}, {@code MALFORMED} — handled inside the service, per pair or series.
 * </ul>
 *
 * <p>A run never overlaps another: one that finds the previous still going is skipped, and the next picks up
 * whatever it would have done, because every step resumes from stored data. One instance, no distributed lock
 * (D-39). The thread is never held for a refusal; continuing is a task handed to the {@link TaskScheduler}.
 *
 * <p>Rule: NSF-04; BR-10, BR-11; TECHNICAL_DESIGN 7.1 step 8, 7.1.2 (the caller contract) and 10.
 */
@Component
public class FuturesMetricsJob {

    private static final Logger log = LoggerFactory.getLogger(FuturesMetricsJob.class);

    /** What one run came to. */
    public enum Outcome {

        /** Settlements and metrics are as current as the exchange and the request limit allow. */
        COMPLETED,

        /** Refused with a known instant; run again then. */
        CONTINUATION_SCHEDULED,

        /** Stopped by an outage or an unexpected failure; the next scheduled run continues. */
        WAIT_FOR_NEXT_RUN,

        /** The previous run was still going; this one did nothing. */
        SKIPPED
    }

    private final FuturesMetricsService metrics;
    private final TaskScheduler scheduler;
    private final FuturesMetricsProperties properties;
    private final ReentrantLock running = new ReentrantLock();
    private ScheduledFuture<?> pending;

    public FuturesMetricsJob(
            FuturesMetricsService metrics, TaskScheduler scheduler, FuturesMetricsProperties properties) {
        this.metrics = metrics;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Schedules the runs, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-04 futures metrics are disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), properties.zone()));
        log.info("NSF-04 scheduled on '{}' ({})", properties.cron(), properties.zone());
    }

    /** One run: settlements, then metrics. */
    public Outcome run() {
        if (!running.tryLock()) {
            log.info("NSF-04 previous run still going; skipped");
            return Outcome.SKIPPED;
        }
        try {
            SettlementRun settlement = metrics.settleFunding();
            log.info(
                    "NSF-04 funding: {} pairs, {} due, {} stored, {} without mark price, defects {}",
                    settlement.pairsChecked(),
                    settlement.pairsDue(),
                    settlement.rowsInserted(),
                    settlement.withoutMarkPrice(),
                    settlement.defects());
            MetricsRun collected = metrics.collectMetrics();
            log.info(
                    "NSF-04 metrics: {} open interest, {} long/short, {} requests{}, defects {}",
                    collected.openInterestRows(),
                    collected.longShortRows(),
                    collected.requests(),
                    collected.stoppedAtRequestLimit() ? " (request limit; continued next run)" : "",
                    collected.defects());
            return Outcome.COMPLETED;
        } catch (BinanceClientException refusal) {
            if (refusal.retryAt().isPresent()) {
                continueAt(refusal.retryAt().get());
                log.warn(
                        "NSF-04 refused ({}); running again at {}",
                        refusal.kind(),
                        refusal.retryAt().get());
                return Outcome.CONTINUATION_SCHEDULED;
            }
            log.warn("NSF-04 stopped ({}); the next scheduled run continues", refusal.kind(), refusal);
            return Outcome.WAIT_FOR_NEXT_RUN;
        } catch (RuntimeException unexpected) {
            log.error("NSF-04 failed unexpectedly; the next scheduled run continues", unexpected);
            return Outcome.WAIT_FOR_NEXT_RUN;
        } finally {
            running.unlock();
        }
    }

    private synchronized void continueAt(Instant at) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::run, at);
    }
}
