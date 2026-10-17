package com.cryptopilot.common.job;

import com.cryptopilot.common.config.OutboxProperties;
import com.cryptopilot.common.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs the outbox processor of T-056 once the application is ready and then every {@code interval}, so the deliveries
 * a restart interrupted are retried as well. Orchestration only; the rules are in {@link OutboxService}. One instance,
 * no distributed lock: a resubmission claims its row first, so two runs never resubmit the same one.
 *
 * <p>Rule: NSF-15; ADR-010.
 */
@Component
public class OutboxProcessorJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessorJob.class);

    private final OutboxService outbox;
    private final TaskScheduler scheduler;
    private final OutboxProperties properties;

    public OutboxProcessorJob(OutboxService outbox, TaskScheduler scheduler, OutboxProperties properties) {
        this.outbox = outbox;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the runs, when enabled; the first one starts at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Outbox processor is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info(
                "Outbox processor scheduled every {}, {} retries from {}",
                properties.interval(),
                properties.maxRetries(),
                properties.firstRetryAfter());
    }

    /** One run; a failure is logged and left for the next run. */
    public void run() {
        try {
            outbox.retryFailedDeliveries();
            outbox.deleteExpired();
        } catch (RuntimeException failure) {
            log.error("Outbox processor run failed; the next run retries", failure);
        }
    }
}
