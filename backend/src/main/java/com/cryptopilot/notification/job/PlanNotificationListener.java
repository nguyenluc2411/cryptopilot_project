package com.cryptopilot.notification.job;

import com.cryptopilot.notification.service.PlanNotificationService;
import com.cryptopilot.trading.event.TradingPlanWithdrawn;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner of a plan cancelled by BR-59. Delivered through the event publication registry after the
 * administrator's change commits, in a transaction of its own (D-98); a failure is delivered again. Orchestration only.
 *
 * <p>Rule: BR-59; D-98; NSF-15.
 */
@Component
public class PlanNotificationListener {

    private final PlanNotificationService notifications;

    public PlanNotificationListener(PlanNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(TradingPlanWithdrawn withdrawn) {
        notifications.withdrawn(withdrawn);
    }
}
