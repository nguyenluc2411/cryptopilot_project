package com.cryptopilot.trading.job;

import com.cryptopilot.trading.config.PlanExpiryProperties;
import com.cryptopilot.trading.service.PlanExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs NSF-09 every minute: the ACTIVE plans whose expiry has come are expired. Orchestration only; the rule is in
 * {@link PlanExpiryService}. One instance, no distributed lock: the compare-and-set on the status expires a plan once.
 *
 * <p>Rule: NSF-09; D-79.
 */
@Component
public class PlanExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PlanExpiryJob.class);

    private final PlanExpiryService expiry;
    private final TaskScheduler scheduler;
    private final PlanExpiryProperties properties;

    public PlanExpiryJob(PlanExpiryService expiry, TaskScheduler scheduler, PlanExpiryProperties properties) {
        this.expiry = expiry;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the runs, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-09 plan expiry is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info("NSF-09 plan expiry scheduled every {}", properties.interval());
    }

    /** One run; a failure is logged and left for the next run. */
    public void run() {
        try {
            int expired = expiry.expireDue(properties.batchSize());
            if (expired > 0) {
                log.info("NSF-09 expired {} plans", expired);
            }
        } catch (RuntimeException failure) {
            log.error("NSF-09 plan expiry failed; the next run retries", failure);
        }
    }
}
