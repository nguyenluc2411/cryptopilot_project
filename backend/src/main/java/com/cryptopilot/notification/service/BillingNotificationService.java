package com.cryptopilot.notification.service;

import com.cryptopilot.billing.event.SubscriptionOrderPaid;

/**
 * The in-app notices of payments. Implemented by
 * {@link com.cryptopilot.notification.service.impl.BillingNotificationServiceImpl}.
 *
 * <p>Rule: BR-53; NSF-15.
 */
public interface BillingNotificationService {

    /** Tells the Trader the payment arrived and which plan is active until when; once however often it is delivered. */
    void paid(SubscriptionOrderPaid event);
}
