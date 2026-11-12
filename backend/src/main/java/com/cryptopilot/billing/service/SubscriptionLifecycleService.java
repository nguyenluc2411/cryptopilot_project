package com.cryptopilot.billing.service;

/**
 * The hourly upkeep of subscriptions (NSF-14): ending the ones past their end, which drops the Trader to FREE (BR-64),
 * and telling Traders whose subscription ends within 3 days. Each subscription is handled in a transaction of its
 * own, so one failure leaves the others done.
 *
 * <p>Rule: NSF-14; BR-64; D-147 (no SCHEDULED subscription is activated any more).
 */
public interface SubscriptionLifecycleService {

    /**
     * Expires the ACTIVE subscriptions past their end and announces each drop to FREE ({@code PlanDowngraded}).
     *
     * @return how many were expired
     */
    int expireEnded();

    /**
     * Announces once each ACTIVE subscription ending within 3 days ({@code SubscriptionEndingSoon}).
     *
     * @return how many Traders were told
     */
    int remindEnding();
}
