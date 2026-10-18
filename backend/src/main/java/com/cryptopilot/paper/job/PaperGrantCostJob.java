package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperGrantCostProperties;
import com.cryptopilot.paper.service.impl.GrantCosts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Values the granted coins that had no price when their account opened, every {@code interval} (PT-R13).
 * Orchestration only; the rule is in {@link GrantCosts}. A balance valued once is not valued again, so a second
 * instance does nothing twice.
 *
 * <p>Rule: TR-13; PT-R13.
 */
@Component
public class PaperGrantCostJob {

    private static final Logger log = LoggerFactory.getLogger(PaperGrantCostJob.class);

    private final GrantCosts grantCosts;
    private final TaskScheduler scheduler;
    private final PaperGrantCostProperties properties;

    public PaperGrantCostJob(GrantCosts grantCosts, TaskScheduler scheduler, PaperGrantCostProperties properties) {
        this.grantCosts = grantCosts;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the runs, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("PT-R13 paper grant cost backfill is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info("PT-R13 paper grant cost backfill every {}", properties.interval());
    }

    /** One run; a failure is logged and left for the next run. */
    public void run() {
        try {
            int valued = grantCosts.valueAll();
            if (valued > 0) {
                log.info("PT-R13 valued {} granted paper balances", valued);
            }
        } catch (RuntimeException failure) {
            log.warn("PT-R13 paper grant cost backfill failed: {}", failure.toString());
        }
    }
}
