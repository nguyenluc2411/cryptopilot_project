package com.cryptopilot.market.job;

import com.cryptopilot.market.config.MarketHubProperties;
import com.cryptopilot.market.service.MarketBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Pushes the market topics on the rhythm of TECHNICAL_DESIGN 9 when the hub is enabled: ticker and kline updates every
 * {@code updateInterval} (1 s), each market's overview every {@code overviewInterval} (2 s).
 *
 * <p>Rule: NSF-03; SRS 4.2.3; TECHNICAL_DESIGN 9 and 10.
 */
@Component
public class MarketHubJob {

    private static final Logger log = LoggerFactory.getLogger(MarketHubJob.class);

    private final MarketBroadcastService broadcast;
    private final TaskScheduler scheduler;
    private final MarketHubProperties properties;

    public MarketHubJob(MarketBroadcastService broadcast, TaskScheduler scheduler, MarketHubProperties properties) {
        this.broadcast = broadcast;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Schedules both pushes when the hub is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Market topics are disabled");
            return;
        }
        scheduler.scheduleAtFixedRate(broadcast::pushUpdates, properties.updateInterval());
        scheduler.scheduleAtFixedRate(broadcast::pushOverviews, properties.overviewInterval());
        log.info(
                "Market topics pushed every {}, overviews every {}",
                properties.updateInterval(),
                properties.overviewInterval());
    }
}
