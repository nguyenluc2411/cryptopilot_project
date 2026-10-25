package com.cryptopilot.notification.job;

import com.cryptopilot.news.event.NewsSourceFailing;
import com.cryptopilot.notification.service.SystemNotificationService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns the news module's events into notices to administrators (NSF-10). Like the community's events, each reaches
 * this class through the event publication registry, after the crawl that recorded it commits, in a transaction of
 * its own; a failure propagates so the event is delivered again. Orchestration only: the rules are in
 * {@link SystemNotificationService}.
 *
 * <p>Rule: NSF-10; NSF-15.
 */
@Component
public class NewsNotificationListener {

    private final SystemNotificationService notifications;

    public NewsNotificationListener(SystemNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(NewsSourceFailing failing) {
        notifications.newsSourceFailing(failing);
    }
}
