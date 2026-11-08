package com.cryptopilot.notification.job;

import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.service.BillingNotificationService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns the payments of the billing module into in-app notices. Each event arrives through the event publication
 * registry after the settlement commits, so a rolled back settlement notifies nothing.
 *
 * <p>Rule: BR-53; NSF-15.
 */
@Component
public class BillingNotificationListener {

    private final BillingNotificationService notifications;

    public BillingNotificationListener(BillingNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(SubscriptionOrderPaid paid) {
        notifications.paid(paid);
    }
}
