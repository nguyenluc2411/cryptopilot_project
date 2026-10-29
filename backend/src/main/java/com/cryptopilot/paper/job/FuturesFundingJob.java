package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperFundingProperties;
import com.cryptopilot.paper.service.impl.FuturesFunding;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Settles the funding of paper Futures positions every {@code interval} (TR-24, plan 9.4): each settlement stored
 * within {@code lookback} that a position owes and was not settled yet, each in a transaction of its own under the
 * Trader's lock, so one that fails is tried again by the next run and the others still go.
 *
 * <p>Rule: TR-24.
 */
@Component
public class FuturesFundingJob {

    private static final Logger log = LoggerFactory.getLogger(FuturesFundingJob.class);

    private final FuturesFunding funding;
    private final TaskScheduler scheduler;
    private final PaperFundingProperties properties;
    private final Clock clock;

    public FuturesFundingJob(
            FuturesFunding funding, TaskScheduler scheduler, PaperFundingProperties properties, Clock clock) {
        this.funding = funding;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /** Registers the runs, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("TR-24 paper Futures funding is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info("TR-24 paper Futures funding every {}", properties.interval());
    }

    /**
     * One run.
     *
     * @return how many fees it wrote
     */
    public int run() {
        Instant now = clock.instant();
        int written = 0;
        try {
            for (FuturesFunding.Owed owed : funding.owed(now.minus(properties.lookback()), now)) {
                try {
                    if (funding.settle(owed)) {
                        written++;
                    }
                } catch (RuntimeException failure) {
                    log.warn(
                            "TR-24 funding of position {} at {} not settled: {}",
                            owed.positionId(),
                            owed.settlement().fundingTime(),
                            failure.toString());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("TR-24 paper Futures funding run failed: {}", failure.toString());
        }
        if (written > 0) {
            log.info("TR-24 settled {} paper Futures funding fees", written);
        }
        return written;
    }
}
