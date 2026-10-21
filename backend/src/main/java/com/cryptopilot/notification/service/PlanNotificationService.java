package com.cryptopilot.notification.service;

import com.cryptopilot.trading.event.TradingPlanWithdrawn;

/**
 * The in-app notifications of trading plans that their owners did not cause: a plan cancelled because an administrator
 * took its pair's market out of service (BR-59). Each event writes its notification once however often it is
 * delivered. Implemented by {@link com.cryptopilot.notification.service.impl.PlanNotificationServiceImpl}.
 *
 * <p>Rule: BR-59; SRS 3.4.5; NSF-15.
 */
public interface PlanNotificationService {

    /** Tells the owner that the plan was cancelled and why. */
    void withdrawn(TradingPlanWithdrawn event);
}
