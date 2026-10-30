package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.service.FuturesPositionWarningService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Every {@code position-warning-interval}, looks at the held paper Futures positions for the warnings of TR-27, one
 * Trader at a time: a Trader whose positions cannot be valued now (a mark missing) is looked at again next time.
 *
 * <p>Rule: TR-27.
 */
@Component
public class FuturesPositionWarningJob {

    private static final Logger log = LoggerFactory.getLogger(FuturesPositionWarningJob.class);

    private final FuturesPositionWarningService warnings;
    private final TaskScheduler scheduler;
    private final PaperMatchingProperties matching;
    private final PaperFuturesProperties futures;
    private final Clock clock;

    public FuturesPositionWarningJob(
            FuturesPositionWarningService warnings,
            TaskScheduler scheduler,
            PaperMatchingProperties matching,
            PaperFuturesProperties futures,
            Clock clock) {
        this.warnings = warnings;
        this.scheduler = scheduler;
        this.matching = matching;
        this.futures = futures;
        this.clock = clock;
    }

    /** Registers the runs while the matching engine is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!matching.enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, futures.positionWarningInterval());
        log.info("TR-27 held Futures positions looked at every {}", futures.positionWarningInterval());
    }

    /** One look at every Trader holding a position. */
    void run() {
        Instant at = clock.instant();
        int sent = 0;
        for (UUID userId : warnings.holders()) {
            try {
                sent += warnings.warn(userId, at);
            } catch (RuntimeException failure) {
                log.debug("TR-27 positions of user {} not looked at now: {}", userId, failure.toString());
            }
        }
        if (sent > 0) {
            log.info("TR-27 {} held-position warnings sent", sent);
        }
    }
}
