package com.cryptopilot.market.job;

import com.cryptopilot.market.config.CoinListProperties;
import com.cryptopilot.market.service.CoinMarketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the market list of coins at start-up and every {@code refresh-interval}, so a request never waits for a
 * build. Nothing when disabled.
 *
 * <p>Rule: SRS 3.3.1.
 */
@Component
public class CoinListJob {

    private static final Logger log = LoggerFactory.getLogger(CoinListJob.class);

    private final CoinMarketService coins;
    private final TaskScheduler scheduler;
    private final CoinListProperties properties;

    public CoinListJob(CoinMarketService coins, TaskScheduler scheduler, CoinListProperties properties) {
        this.coins = coins;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** One rebuild now, then one every {@code refresh-interval}. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Coin list rebuild is disabled");
            return;
        }
        scheduler.scheduleAtFixedRate(coins::refresh, properties.refreshInterval());
    }
}
