package com.cryptopilot.billing.job;

import com.cryptopilot.billing.config.SubscriptionSweepProperties;
import com.cryptopilot.billing.service.SubscriptionLifecycleService;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Expires the subscriptions past their end and reminds the ones ending within 3 days, hourly when enabled (NSF-14).
 * Single backend instance (TECHNICAL_DESIGN 1.3); the billing lock and the once-only reminder mark keep a second run
 * from repeating anything anyway.
 *
 * <p>Rule: NSF-14; BR-64; D-146.
 */
@Component
public class SubscriptionSweepJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSweepJob.class);

    private final SubscriptionLifecycleService lifecycle;
    private final TaskScheduler scheduler;
    private final SubscriptionSweepProperties properties;

    public SubscriptionSweepJob(
            SubscriptionLifecycleService lifecycle, TaskScheduler scheduler, SubscriptionSweepProperties properties) {
        this.lifecycle = lifecycle;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the run, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Subscription sweep is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), ZoneOffset.UTC));
        log.info("Subscription sweep scheduled on '{}' (UTC)", properties.cron());
    }

    /** One run: expiry first, so a subscription that ended is never reminded; a failure waits for the next hour. */
    public void run() {
        try {
            int expired = lifecycle.expireEnded();
            int reminded = lifecycle.remindEnding();
            if (expired > 0 || reminded > 0) {
                log.info("Subscription sweep expired {} and reminded {} subscriptions", expired, reminded);
            }
        } catch (RuntimeException failure) {
            log.error("Subscription sweep failed; the next run retries", failure);
        }
    }
}
