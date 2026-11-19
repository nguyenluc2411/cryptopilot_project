package com.cryptopilot.notification.job;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.notification.config.MarketBroadcastProperties;
import com.cryptopilot.notification.service.MarketNotificationService;
import java.time.Clock;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Tells every other Trader of an announced market incident (D-158): {@code delay} after the pause is announced, and
 * after an announced pause ends, one general notice in the bell to each active Trader who got no notice of their own,
 * by then written for the Traders the incident affects. A pause never announced tells no one else. Orchestration only.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Component
public class MarketBroadcastJob {

    private static final Logger log = LoggerFactory.getLogger(MarketBroadcastJob.class);

    private final MarketNotificationService notifications;
    private final MarketBroadcastProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public MarketBroadcastJob(
            MarketNotificationService notifications,
            MarketBroadcastProperties properties,
            TaskScheduler scheduler,
            Clock clock) {
        this.notifications = notifications;
        this.properties = properties;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(MarketPauseAnnounced announced) {
        later(() -> notifications.broadcastPause(announced), announced.market() + " pause");
    }

    @ApplicationModuleListener
    public void on(MarketTradingResumed resumed) {
        if (resumed.announced()) {
            later(() -> notifications.broadcastResume(resumed), resumed.market() + " reopening");
        }
    }

    private void later(IntSupplier broadcast, String what) {
        if (!properties.enabled()) {
            return;
        }
        scheduler.schedule(
                () -> {
                    try {
                        log.info("D-158 {} told to {} other Traders", what, broadcast.getAsInt());
                    } catch (RuntimeException failure) {
                        log.warn("D-158 {} not told to the other Traders: {}", what, failure.toString());
                    }
                },
                clock.instant().plus(properties.delay()));
    }
}
