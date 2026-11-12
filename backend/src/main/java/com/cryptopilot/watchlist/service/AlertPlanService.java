package com.cryptopilot.watchlist.service;

import com.cryptopilot.billing.event.PlanDowngraded;

/**
 * Brings a Trader's alerts within the plan held now, after the plan dropped (BR-64).
 *
 * <p>Rule: BR-64; BR-17.
 */
public interface AlertPlanService {

    /**
     * Pauses the ACTIVE alerts the plan held now does not allow: indicator alerts without {@code INDICATOR_ALERT},
     * Futures alerts without {@code FUTURES_ANALYSIS}, then the oldest of the rest above {@code ACTIVE_ALERT_MAX}, so
     * the most recently created ones keep running. Nothing is deleted; email and push are dropped at delivery (NSF-15).
     * Announces the result ({@code AlertsPausedForPlan}).
     *
     * @return how many alerts were paused
     */
    int applyPlan(PlanDowngraded downgrade);
}
