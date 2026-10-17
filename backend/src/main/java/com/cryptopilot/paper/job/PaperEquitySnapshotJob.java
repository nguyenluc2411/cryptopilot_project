package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperEquityProperties;
import com.cryptopilot.paper.service.PaperPnlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Takes the daily equity snapshot of every paper account at 00:00 UTC (TR-13). Orchestration only; the rule is in
 * {@link PaperPnlService}. One instance, no distributed lock: a snapshot is taken once per account and day, so a
 * second run takes none.
 *
 * <p>Rule: TR-13.
 */
@Component
public class PaperEquitySnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PaperEquitySnapshotJob.class);

    private final PaperPnlService pnl;
    private final TaskScheduler scheduler;
    private final PaperEquityProperties properties;

    public PaperEquitySnapshotJob(PaperPnlService pnl, TaskScheduler scheduler, PaperEquityProperties properties) {
        this.pnl = pnl;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the daily run, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("TR-13 paper equity snapshot is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), properties.zone()));
        log.info("TR-13 paper equity snapshot scheduled on '{}' ({})", properties.cron(), properties.zone());
    }

    /** One run; a failure is logged and left for the next day's run. */
    public void run() {
        try {
            log.info("TR-13 took {} paper equity snapshots", pnl.snapshotAll());
        } catch (RuntimeException failure) {
            log.error("TR-13 paper equity snapshot failed; the next run retries", failure);
        }
    }
}
