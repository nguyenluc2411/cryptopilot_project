package com.cryptopilot.paper.job;

import com.cryptopilot.market.MarketHealth;
import com.cryptopilot.market.model.enums.MarketType;
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
    private final MarketHealth health;
    private final TaskScheduler scheduler;
    private final PaperFundingProperties properties;
    private final Clock clock;

    public FuturesFundingJob(
            FuturesFunding funding,
            MarketHealth health,
            TaskScheduler scheduler,
            PaperFundingProperties properties,
            Clock clock) {
        this.funding = funding;
        this.health = health;
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
     * One run; none while Futures trading is paused (D-158, I5): the replay that follows may show a position closed
     * before a funding time, so the settlements wait for it, and the next run reaches back to them.
     *
     * @return how many fees it wrote
     */
    /**
     * Where a run looks back from: the lookback, or further while an incident just ended, to where its data stopped,
     * so the settlements that fell due while the market was closed are taken (D-158).
     */
    private Instant from(Instant now) {
        Instant lookback = now.minus(properties.lookback());
        return health.catchUpFrom(MarketType.FUTURES)
                .filter(from -> from.isBefore(lookback))
                .orElse(lookback);
    }

    public int run() {
        if (!health.tradingOpen(MarketType.FUTURES)) {
            return 0;
        }
        Instant now = clock.instant();
        int written = 0;
        try {
            for (FuturesFunding.Owed owed : funding.owed(from(now), now)) {
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
