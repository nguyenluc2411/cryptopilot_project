package com.cryptopilot.notification.job;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
import com.cryptopilot.notification.service.MarketNotificationService;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.trading.event.PlanIncidentNotice;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns the market incidents into notices (D-158): the paper and plan modules name the Traders they affect, the market
 * announces the pause to the administrators. Each event arrives through the event publication registry after its
 * commit, in a transaction of its own; a failure propagates so it is delivered again. Orchestration only: the texts
 * are in {@link MarketNotificationService}.
 *
 * <p>Rule: NSF-03; NSF-15; D-158.
 */
@Component
public class MarketNotificationListener {

    private final MarketNotificationService notifications;

    public MarketNotificationListener(MarketNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(PaperIncidentNotice notice) {
        notifications.paperIncident(notice);
    }

    @ApplicationModuleListener
    public void on(PlanIncidentNotice notice) {
        notifications.planIncident(notice);
    }

    @ApplicationModuleListener
    public void on(MarketPauseAnnounced announced) {
        notifications.pauseAnnounced(announced);
    }

    @ApplicationModuleListener
    public void on(MarketRecoveryOverdue overdue) {
        notifications.recoveryOverdue(overdue);
    }
}
