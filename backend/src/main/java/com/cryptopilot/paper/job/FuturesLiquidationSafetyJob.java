package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Every {@code liquidation-max-duration}, has the matching engine judge again the cross liquidations that run longer
 * than that and resume them (TR-23, plan 9.3): a {@code ForceClose} lost, or a pair whose mark was missing, never
 * leaves an account refusing orders for good. The engine also does it once at start.
 *
 * <p>Rule: TR-23.
 */
@Component
public class FuturesLiquidationSafetyJob {

    private static final Logger log = LoggerFactory.getLogger(FuturesLiquidationSafetyJob.class);

    private final PaperMatchingWorker worker;
    private final TaskScheduler scheduler;
    private final PaperMatchingProperties matching;
    private final PaperFuturesProperties futures;

    public FuturesLiquidationSafetyJob(
            PaperMatchingWorker worker,
            TaskScheduler scheduler,
            PaperMatchingProperties matching,
            PaperFuturesProperties futures) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.matching = matching;
        this.futures = futures;
    }

    /** Registers the runs while the matching engine is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!matching.enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(worker::resumeLiquidations, futures.liquidationMaxDuration());
        log.info("TR-23 running cross liquidations judged again every {}", futures.liquidationMaxDuration());
    }
}
