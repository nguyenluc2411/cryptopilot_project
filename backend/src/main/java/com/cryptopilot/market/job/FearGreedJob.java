package com.cryptopilot.market.job;

import com.cryptopilot.market.client.FearGreedClient;
import com.cryptopilot.market.client.FearGreedException;
import com.cryptopilot.market.model.MarketReferenceStore;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

/**
 * Reads the Fear &amp; Greed Index at start-up and every {@link #EVERY}. It is published once a day, so an hour is
 * plenty; a failed read keeps the last good one.
 *
 * <p>Rule: SRS 3.3.1.
 */
public class FearGreedJob {

    private static final Logger log = LoggerFactory.getLogger(FearGreedJob.class);

    /** How often the index is read. */
    static final Duration EVERY = Duration.ofHours(1);

    private final FearGreedClient client;
    private final MarketReferenceStore store;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public FearGreedJob(FearGreedClient client, MarketReferenceStore store, TaskScheduler scheduler, Clock clock) {
        this.client = client;
        this.store = store;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** One read now, then one every {@link #EVERY}. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleAtFixedRate(this::run, clock.instant(), EVERY);
    }

    /** One read. */
    public void run() {
        try {
            store.fearGreed(client.latest());
        } catch (FearGreedException failure) {
            log.warn("Fear & Greed Index failed ({}); the last reading stays", failure.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("Fear & Greed Index failed unexpectedly", unexpected);
        }
    }
}
