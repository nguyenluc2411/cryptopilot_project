package com.cryptopilot.notification.job;

import com.cryptopilot.notification.config.NotificationSweepProperties;
import com.cryptopilot.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs the notification part of NSF-17 daily: notifications older than the retention period (90 days, SRS 3.4.5) are
 * deleted. Orchestration only; the rule is in {@link NotificationService#removeExpired()}. One instance, no
 * distributed lock: the delete is idempotent.
 *
 * <p>Rule: NSF-17; SRS 3.4.5.
 */
@Component
public class NotificationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweepJob.class);

    private final NotificationService notifications;
    private final TaskScheduler scheduler;
    private final NotificationSweepProperties properties;

    public NotificationSweepJob(
            NotificationService notifications, TaskScheduler scheduler, NotificationSweepProperties properties) {
        this.notifications = notifications;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the daily run, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-17 notification sweep is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), properties.zone()));
        log.info("NSF-17 notification sweep scheduled on '{}' ({})", properties.cron(), properties.zone());
    }

    /** One sweep; a failure is logged and left for the next day's run. */
    public void run() {
        try {
            int deleted = notifications.removeExpired();
            log.info("NSF-17 deleted {} notifications older than {}", deleted, properties.retention());
        } catch (RuntimeException failure) {
            log.error("NSF-17 notification sweep failed; the next run retries", failure);
        }
    }
}
