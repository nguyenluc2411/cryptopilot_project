package com.cryptopilot.notification.job;

import com.cryptopilot.notification.service.FuturesNotificationService;
import com.cryptopilot.paper.event.FuturesRiskNotice;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns the paper module's risk events into notices to the Trader (TR-43). Like the other modules' events, each
 * reaches this class through the event publication registry, after the change commits, in a transaction of its own; a
 * failure propagates so the event is delivered again. Orchestration only: the rules are in
 * {@link FuturesNotificationService}.
 *
 * <p>Rule: TR-43; NSF-15.
 */
@Component
public class FuturesNotificationListener {

    private final FuturesNotificationService notifications;

    public FuturesNotificationListener(FuturesNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(FuturesRiskNotice notice) {
        notifications.riskNotice(notice);
    }
}
