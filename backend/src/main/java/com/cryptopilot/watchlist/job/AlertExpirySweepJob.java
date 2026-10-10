package com.cryptopilot.watchlist.job;

import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.AlertExpiryService;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Sets alerts past their expiry EXPIRED on a schedule (every minute by default). Orchestration only; the rule is in
 * {@link AlertExpiryService}. One instance, no distributed lock: the update is conditional and idempotent. The engine
 * already skips an expired alert before the sweep reaches it.
 *
 * <p>Rule: BR-19; SRS 3.4.4.
 */
@Component
public class AlertExpirySweepJob {

    private static final Logger log = LoggerFactory.getLogger(AlertExpirySweepJob.class);

    private final AlertExpiryService expiry;
    private final TaskScheduler scheduler;
    private final AlertEngineProperties properties;

    public AlertExpirySweepJob(AlertExpiryService expiry, TaskScheduler scheduler, AlertEngineProperties properties) {
        this.expiry = expiry;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the sweep, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        AlertEngineProperties.ExpirySweep sweep = properties.expirySweep();
        if (!sweep.enabled()) {
            log.info("BR-19 alert expiry sweep is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(sweep.cron(), ZoneId.of(sweep.zone())));
        log.info("BR-19 alert expiry sweep scheduled on '{}' ({})", sweep.cron(), sweep.zone());
    }

    /** One sweep; a failure is logged and left for the next run. */
    public void run() {
        try {
            int expired = expiry.expireDue();
            if (expired > 0) {
                log.info("BR-19 {} alerts expired", expired);
            }
        } catch (RuntimeException failure) {
            log.error("BR-19 alert expiry sweep failed; the next run retries", failure);
        }
    }
}
