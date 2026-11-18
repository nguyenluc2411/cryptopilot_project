package com.cryptopilot.market.job;

import com.cryptopilot.market.service.MarketReferenceService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

/**
 * Refreshes CoinPaprika's figures of the market list at start-up and every {@link #EVERY}: the whole market's cap each
 * time, the coins' supplies hourly (see {@link MarketReferenceService}). About 150 calls for the cap and 24 for the
 * supplies a day, well inside the free plan. One run at a time; nothing here is thrown into the scheduler.
 *
 * <p>Rule: SRS 3.3.1.
 */
public class MarketReferenceJob {

    /** How often the figures are refreshed. */
    static final Duration EVERY = Duration.ofMinutes(10);

    private final MarketReferenceService reference;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public MarketReferenceJob(MarketReferenceService reference, TaskScheduler scheduler, Clock clock) {
        this.reference = reference;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** One run now, then one every {@link #EVERY}. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleAtFixedRate(this::run, clock.instant(), EVERY);
    }

    /** One refresh, unless one is in progress. */
    public void run() {
        if (!running.tryLock()) {
            return;
        }
        try {
            reference.refresh();
        } finally {
            running.unlock();
        }
    }
}
