package com.cryptopilot.notification.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.AlertNotificationService;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.watchlist.event.AlertTriggered;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers an alert that fired. In the app every trigger is a notification (NSF-15) whose source key is the alert and
 * its trigger count, so a redelivered event writes nothing and two triggers of one alert are two notifications.
 *
 * <p>By mail only when the alert asks for it, and only while the Trader's plan includes {@code EXTERNAL_ALERT_CHANNELS}:
 * it is checked when the alert was saved and again here, because a plan can lapse while the alert stays active.
 * The account must also be active, verified and keep notification mail on. The mail holds no transaction while the
 * mail server answers; a failure is thrown, so the event stays unfinished and the outbox sends it again.
 *
 * <p>Rule: NSF-06, NSF-15, BR-62; SRS 3.4.4, 3.4.5.
 */
@Service
@RequiredArgsConstructor
public class AlertNotificationServiceImpl implements AlertNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationServiceImpl.class);

    private final NotificationWriter writer;
    private final AlertMails mails;
    private final EmailSender sender;
    private final UserApi users;
    private final EntitlementApi entitlements;

    @Override
    @Transactional
    public void notifyInApp(AlertTriggered event) {
        writer.write(new NewNotification(
                event.userId(),
                NotificationType.ALERT,
                AlertNotices.title(event),
                AlertNotices.content(event),
                NotificationReference.ALERT,
                event.alertId(),
                "ALERT_TRIGGERED:" + event.alertId() + ":" + event.triggerCount()));
    }

    @Override
    public void mail(AlertTriggered event) {
        if (!event.notifyEmail()) {
            return;
        }
        if (!entitlements.hasFeature(event.userId(), Feature.EXTERNAL_ALERT_CHANNELS)) {
            log.debug(
                    "Alert {} not mailed: the plan of {} has no external alert channels",
                    event.alertId(),
                    event.userId());
            return;
        }
        users.notificationMailAddress(event.userId())
                .ifPresentOrElse(
                        address -> sender.send(mails.alert(address, event)),
                        () -> log.debug(
                                "Alert {} not mailed: account {} takes no notification mail",
                                event.alertId(),
                                event.userId()));
    }
}
