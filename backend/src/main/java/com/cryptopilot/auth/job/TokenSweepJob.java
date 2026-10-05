package com.cryptopilot.auth.job;

import com.cryptopilot.auth.config.TokenSweepProperties;
import com.cryptopilot.auth.service.TokenRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs the refresh-token part of NSF-17 daily: expired refresh tokens are deleted, used ones that have not expired
 * are kept for reuse detection. Orchestration only; the rule is in {@link TokenRetentionService}. One instance, no
 * distributed lock: the delete is idempotent.
 *
 * <p>Rule: NSF-17; TECHNICAL_DESIGN 7.15.
 */
@Component
public class TokenSweepJob {

    private static final Logger log = LoggerFactory.getLogger(TokenSweepJob.class);

    private final TokenRetentionService retention;
    private final TaskScheduler scheduler;
    private final TokenSweepProperties properties;

    public TokenSweepJob(TokenRetentionService retention, TaskScheduler scheduler, TokenSweepProperties properties) {
        this.retention = retention;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the daily run, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-17 refresh-token sweep is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), properties.zone()));
        log.info("NSF-17 refresh-token sweep scheduled on '{}' ({})", properties.cron(), properties.zone());
    }

    /** One sweep; a failure is logged and left for the next day's run. */
    public void run() {
        try {
            int deleted = retention.removeExpiredRefreshTokens();
            log.info("NSF-17 deleted {} expired refresh tokens", deleted);
        } catch (RuntimeException failure) {
            log.error("NSF-17 refresh-token sweep failed; the next run retries", failure);
        }
    }
}
