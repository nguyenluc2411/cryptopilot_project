package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperEquityProperties;
import com.cryptopilot.paper.service.PaperPnlService;
import java.time.Clock;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Takes the daily equity snapshot of every paper account at 00:00 UTC (TR-13), and tries the accounts left without one
 * again every {@code retry-interval} until {@code retry-until}: an account with a coin that has no current price gets
 * none until it has. Orchestration only; the rule is in {@link PaperPnlService}. One instance, no distributed lock: a
 * snapshot is taken once per account and day, so a second run takes none.
 *
 * <p>Rule: TR-13; PT-R02.
 */
@Component
public class PaperEquitySnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PaperEquitySnapshotJob.class);

    private final PaperPnlService pnl;
    private final TaskScheduler scheduler;
    private final PaperEquityProperties properties;
    private final Clock clock;

    public PaperEquitySnapshotJob(
            PaperPnlService pnl, TaskScheduler scheduler, PaperEquityProperties properties, Clock clock) {
        this.pnl = pnl;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /** Registers the daily run and its retries, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("TR-13 paper equity snapshot is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), properties.zone()));
        scheduler.scheduleWithFixedDelay(this::retry, properties.retryInterval());
        log.info(
                "TR-13 paper equity snapshot scheduled on '{}' ({}), retried every {} until {}",
                properties.cron(),
                properties.zone(),
                properties.retryInterval(),
                properties.retryUntil());
    }

    /** One run; a failure is logged and left for the next retry. */
    public void run() {
        try {
            log.info("TR-13 took {} paper equity snapshots", pnl.snapshotAll());
        } catch (RuntimeException failure) {
            log.error("TR-13 paper equity snapshot failed; the next run retries", failure);
        }
    }

    /** Takes the snapshots still missing today, until {@code retry-until}; logs only when it took some. */
    public void retry() {
        if (!LocalTime.ofInstant(clock.instant(), properties.zone()).isBefore(properties.retryUntil())) {
            return;
        }
        try {
            int taken = pnl.snapshotAll();
            if (taken > 0) {
                log.info("TR-13 retry took {} paper equity snapshots", taken);
            }
        } catch (RuntimeException failure) {
            log.warn("TR-13 paper equity snapshot retry failed: {}", failure.toString());
        }
    }
}
