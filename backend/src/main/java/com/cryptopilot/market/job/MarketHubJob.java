package com.cryptopilot.market.job;

import com.cryptopilot.market.config.MarketHubProperties;
import com.cryptopilot.market.service.MarketBroadcastService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Pushes the market topics on the rhythm of TECHNICAL_DESIGN 9 when the hub is enabled: ticker and kline updates every
 * {@code updateInterval} (1 s), each market's overview every {@code overviewInterval} (2 s).
 *
 * <p>Rule: NSF-03; SRS 4.2.3; TECHNICAL_DESIGN 9 and 10.
 */
@Component
public class MarketHubJob {

    private static final Logger log = LoggerFactory.getLogger(MarketHubJob.class);

    /** The destinations of the market overviews, {@code /topic/overview.SPOT} and {@code /topic/overview.FUTURES}. */
    static final String OVERVIEW_TOPIC = "/topic/overview.";

    /** How long a new subscription waits for its first overview: long enough for the broker to register it. */
    static final Duration SUBSCRIBE_SETTLE = Duration.ofMillis(150);

    private final AtomicBoolean overviewQueued = new AtomicBoolean(false);
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
        scheduler.scheduleAtFixedRate(broadcast::pushTrading, properties.tradingInterval());
        log.info(
                "Market topics pushed every {}, overviews every {}, trades and depth every {}",
                properties.updateInterval(),
                properties.overviewInterval(),
                properties.tradingInterval());
    }

    /**
     * A client has just subscribed to an overview: the overviews are pushed again shortly, so the market board fills in
     * as soon as the connection is up instead of at the next scheduled push (up to {@code overviewInterval} later). The
     * push waits {@link #SUBSCRIBE_SETTLE} for the broker to register the subscription, and subscriptions arriving
     * meanwhile share it.
     */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        if (!properties.enabled()) {
            return;
        }
        String destination = StompHeaderAccessor.wrap(event.getMessage()).getDestination();
        if (destination == null || !destination.startsWith(OVERVIEW_TOPIC)) {
            return;
        }
        if (overviewQueued.compareAndSet(false, true)) {
            try {
                scheduler.schedule(
                        () -> {
                            overviewQueued.set(false);
                            broadcast.pushOverviews();
                        },
                        Instant.now().plus(SUBSCRIBE_SETTLE));
            } catch (RuntimeException refused) {
                // The scheduler is shutting down: the next subscription tries again; the regular push still runs.
                overviewQueued.set(false);
            }
        }
    }
}
