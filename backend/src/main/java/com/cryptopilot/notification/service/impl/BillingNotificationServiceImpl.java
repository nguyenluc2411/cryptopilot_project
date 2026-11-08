package com.cryptopilot.notification.service.impl;

import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.BillingNotificationService;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notice of a paid order, in the style of the other notices: one short sentence naming what happened, here the
 * package, the amount and the end of the plan. It opens the plans (SCR-30). The source key is the order's, so a
 * redelivery writes nothing.
 *
 * <p>Rule: BR-53; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class BillingNotificationServiceImpl implements BillingNotificationService {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final NotificationWriter writer;

    @Override
    @Transactional
    public void paid(SubscriptionOrderPaid event) {
        writer.write(new NewNotification(
                event.userId(),
                NotificationType.SUBSCRIPTION,
                "Payment received",
                "Your payment of " + PaymentReceiptMails.money(event.amount(), event.currency()) + " for "
                        + event.packageName() + " was received. Your plan is active until "
                        + DAY.format(event.endAt()) + ".",
                NotificationReference.SUBSCRIPTION,
                event.orderId(),
                "ORDER_PAID:" + event.orderId()));
    }
}
