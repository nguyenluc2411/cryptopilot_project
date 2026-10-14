package com.cryptopilot.watchlist.job;

import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.FundingRateAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * NSF-06 for FUNDING_RATE alerts: once per {@code throttle} (1s, SRS 3.4.4), like the PRICE alerts of a pair, the
 * current predicted funding rates are evaluated. Runs where the alert engine runs; a failure is logged and the next
 * run carries on.
 *
 * <p>Rule: NSF-06; SRS 3.4.4.
 */
@Component
public class FundingRateAlertJob {

    private static final Logger log = LoggerFactory.getLogger(FundingRateAlertJob.class);

    private final FundingRateAlertService alerts;
    private final TaskScheduler scheduler;
    private final AlertEngineProperties properties;

    public FundingRateAlertJob(
            FundingRateAlertService alerts, TaskScheduler scheduler, AlertEngineProperties properties) {
        this.alerts = alerts;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the evaluation, when the alert engine is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.throttle());
        log.info("NSF-06 FUNDING_RATE alerts evaluated every {}", properties.throttle());
    }

    /** One evaluation; a failure is logged and left for the next run. */
    public void run() {
        try {
            alerts.evaluate();
        } catch (RuntimeException failure) {
            log.warn("NSF-06 FUNDING_RATE alerts not evaluated: {}", failure.toString());
        }
    }
}
