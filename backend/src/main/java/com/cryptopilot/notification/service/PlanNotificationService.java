package com.cryptopilot.notification.service;

import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.event.TradingPlanWithdrawn;

/**
 * The in-app notifications of trading plans that their owners did not cause: a plan cancelled because an administrator
 * took its pair's market out of service (BR-59). Each event writes its notification once however often it is
 * delivered. Implemented by {@link com.cryptopilot.notification.service.impl.PlanNotificationServiceImpl}.
 *
 * <p>Rule: BR-35, BR-38, BR-59; SRS 3.4.5; NSF-15; Q-56.
 */
public interface PlanNotificationService {

    /** Tells the owner that the plan was cancelled and why. */
    void withdrawn(TradingPlanWithdrawn event);

    /** Tells the owner that a position closed (MSG23) or was liquidated (MSG24), with its net P/L. */
    void positionClosed(PositionClosed event);

    /** Tells the owner that a plan expired before its entry was filled (MSG53). */
    void expired(TradingPlanExpired event);

    /** Tells the owner that a plan was invalidated before its entry was filled, and why (MSG54). */
    void invalidated(TradingPlanInvalidated event);
}
