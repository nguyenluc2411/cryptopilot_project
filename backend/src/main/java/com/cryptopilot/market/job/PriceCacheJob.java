package com.cryptopilot.market.job;

import com.cryptopilot.market.config.PriceCacheProperties;
import com.cryptopilot.market.service.PriceCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Writes the latest prices gathered from the streams to Redis every {@code flushInterval}, when the cache is enabled.
 * The service never throws; a failure waits for the next flush.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6 and 10.
 */
@Component
public class PriceCacheJob {

    private static final Logger log = LoggerFactory.getLogger(PriceCacheJob.class);

    private final PriceCacheService cache;
    private final TaskScheduler scheduler;
    private final PriceCacheProperties properties;

    public PriceCacheJob(PriceCacheService cache, TaskScheduler scheduler, PriceCacheProperties properties) {
        this.cache = cache;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Schedules the flush when the cache is enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Latest-price cache is disabled");
            return;
        }
        scheduler.scheduleAtFixedRate(cache::flush, properties.flushInterval());
        log.info("Latest-price cache flushed every {}", properties.flushInterval());
    }
}
