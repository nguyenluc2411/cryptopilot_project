package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.watchlist.event.AlertsPausedForPlan;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The in-app notice of a paid order: its text, what it opens, and a source key per order so a redelivery writes
 * nothing.
 *
 * <p>Rule: BR-53; NSF-15.
 */
class BillingNotificationServiceImplTest {

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final BillingNotificationServiceImpl notifications = new BillingNotificationServiceImpl(writer);

    @Test
    void BR53_aPaidOrder_tellsTheTraderThePackageTheAmountAndTheEnd() {
        SubscriptionOrderPaid paid = PaymentReceiptMailsTest.paid("NEW", "TX-1", "0", 0);

        notifications.paid(paid);

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        NewNotification notice = written.getValue();
        assertThat(notice.title()).isEqualTo("Payment received");
        assertThat(notice.content())
                .isEqualTo(
                        "Your payment of 99.000 ₫ for Pro Monthly was received. Your plan is active until 2026-12-08.");
        assertThat(notice.type()).isEqualTo(NotificationType.SUBSCRIPTION);
        assertThat(notice.referenceType()).isEqualTo(NotificationReference.SUBSCRIPTION);
        assertThat(notice.referenceId()).isEqualTo(paid.orderId());
        assertThat(notice.recipientId()).isEqualTo(paid.userId());
        assertThat(notice.sourceKey()).isEqualTo("ORDER_PAID:" + paid.orderId());
    }

    @Test
    void NSF14_theReminder_namesThePlanAndItsEnd_onceForTheSubscription() {
        SubscriptionEndingSoon soon = new SubscriptionEndingSoon(
                UUID.randomUUID(), UUID.randomUUID(), "Pro Monthly", Instant.parse("2026-12-08T03:00:00Z"));

        notifications.endingSoon(soon);

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        NewNotification notice = written.getValue();
        assertThat(notice.title()).isEqualTo("Your plan ends soon");
        assertThat(notice.content())
                .isEqualTo("Your Pro Monthly plan ends on 2026-12-08. Renew it to keep its features.");
        assertThat(notice.referenceType()).isEqualTo(NotificationReference.SUBSCRIPTION);
        assertThat(notice.referenceId()).isEqualTo(soon.subscriptionId());
        assertThat(notice.recipientId()).isEqualTo(soon.userId());
        assertThat(notice.sourceKey()).isEqualTo("SUBSCRIPTION_ENDING:" + soon.subscriptionId());
    }

    @Test
    void MSG49_aPlanDrop_namesThePlanAndThePausedAlerts() {
        UUID user = UUID.randomUUID();
        Instant at = Instant.parse("2026-12-08T03:00:00Z");

        notifications.planDowngraded(new AlertsPausedForPlan(user, "Free", 3, at));

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        NewNotification notice = written.getValue();
        assertThat(notice.content())
                .isEqualTo("Your plan is now Free. 3 alerts have been paused because of the plan limits.");
        assertThat(notice.type()).isEqualTo(NotificationType.SUBSCRIPTION);
        assertThat(notice.referenceType()).isNull();
        assertThat(notice.sourceKey()).isEqualTo("PLAN_DOWNGRADED:" + user + ":" + at.toEpochMilli());
    }
}
