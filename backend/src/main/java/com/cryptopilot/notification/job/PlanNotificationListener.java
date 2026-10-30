package com.cryptopilot.notification.job;

import com.cryptopilot.notification.service.PlanNotificationService;
import com.cryptopilot.trading.event.PlanFilled;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.event.TradingPlanWithdrawn;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner of a plan what happened to it without their action: its entry filled (MSG22), cancelled by BR-59,
 * expired (MSG53) or invalidated (MSG54), or its position closed or liquidated (MSG23, MSG24). Delivered through the event publication registry after the change commits, in a
 * transaction of its own (D-98); a failure is delivered again. Orchestration only.
 *
 * <p>Rule: BR-34, BR-35, BR-59; MSG22; D-98; NSF-15; Q-56, Q-81.
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

    @ApplicationModuleListener
    public void on(PlanFilled filled) {
        notifications.filled(filled);
    }

    @ApplicationModuleListener
    public void on(PositionClosed closed) {
        notifications.positionClosed(closed);
    }

    @ApplicationModuleListener
    public void on(TradingPlanExpired expired) {
        notifications.expired(expired);
    }

    @ApplicationModuleListener
    public void on(TradingPlanInvalidated invalidated) {
        notifications.invalidated(invalidated);
    }
}
