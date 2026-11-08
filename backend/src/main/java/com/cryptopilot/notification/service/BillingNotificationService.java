package com.cryptopilot.notification.service;

import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.watchlist.event.AlertsPausedForPlan;

/**
 * The in-app notices of payments. Implemented by
 * {@link com.cryptopilot.notification.service.impl.BillingNotificationServiceImpl}.
 *
 * <p>Rule: BR-53; NSF-15.
 */
public interface BillingNotificationService {

    /** Tells the Trader the payment arrived and which plan is active until when; once however often it is delivered. */
    void paid(SubscriptionOrderPaid event);

    /** Tells the Trader the subscription ends within 3 days (NSF-14); once per subscription. */
    void endingSoon(SubscriptionEndingSoon event);

    /** Tells the Trader the plan dropped and how many alerts were paused (MSG49, BR-64); once per drop. */
    void planDowngraded(AlertsPausedForPlan event);
}
