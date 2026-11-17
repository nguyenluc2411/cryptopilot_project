package com.cryptopilot.community.job;

import com.cryptopilot.community.config.CoinSentimentProperties;
import com.cryptopilot.community.service.CoinSentimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Recomputes the community's leaning per coin at start-up and every {@code refresh-interval}. Nothing when disabled.
 *
 * <p>Rule: D-125.
 */
@Component
public class CoinSentimentJob {

    private static final Logger log = LoggerFactory.getLogger(CoinSentimentJob.class);

    private final CoinSentimentService sentiment;
    private final TaskScheduler scheduler;
    private final CoinSentimentProperties properties;

    public CoinSentimentJob(
            CoinSentimentService sentiment, TaskScheduler scheduler, CoinSentimentProperties properties) {
        this.sentiment = sentiment;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** One recompute now, then one every {@code refresh-interval}. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Coin sentiment recompute is disabled");
            return;
        }
        scheduler.scheduleAtFixedRate(sentiment::refresh, properties.refreshInterval());
    }
}
