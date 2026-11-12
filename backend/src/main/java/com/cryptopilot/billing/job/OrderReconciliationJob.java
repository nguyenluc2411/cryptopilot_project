package com.cryptopilot.billing.job;

import com.cryptopilot.billing.config.ReconcileProperties;
import com.cryptopilot.billing.service.OrderReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Asks the gateways about the orders whose IPN never came, and expires the unpaid ones (NSF-13, BR-54), every
 * {@code cryptopilot.billing.reconcile.interval} when enabled. Single backend instance (TECHNICAL_DESIGN 1.3), so no
 * distributed lock; the row lock of the settlement keeps a run and an IPN from settling one order twice anyway.
 *
 * <p>Rule: NSF-13; BR-54; D-147.
 */
@Component
public class OrderReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationJob.class);

    private final OrderReconciliationService reconciliation;
    private final TaskScheduler scheduler;
    private final ReconcileProperties properties;

    public OrderReconciliationJob(
            OrderReconciliationService reconciliation, TaskScheduler scheduler, ReconcileProperties properties) {
        this.reconciliation = reconciliation;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the run, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Order reconciliation is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info("Order reconciliation scheduled every {}", properties.interval());
    }

    /** One run; a failure is logged and left for the next run. */
    public void run() {
        try {
            int changed = reconciliation.reconcileDue();
            if (changed > 0) {
                log.info("Order reconciliation settled or expired {} orders", changed);
            }
        } catch (RuntimeException failure) {
            log.error("Order reconciliation failed; the next run retries", failure);
        }
    }
}
