package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * An alert that fired is always a notification in the app, written once per trigger; it is mailed only when the alert
 * asks for mail, the plan still includes external alert channels, and the account takes notification mail.
 *
 * <p>Rule: NSF-06, NSF-15, BR-62 ({@code EXTERNAL_ALERT_CHANNELS}); SRS 3.4.4, 3.4.5.
 */
class AlertNotificationServiceImplTest {

    private static final String ADDRESS = "trader@example.com";

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final UserApi users = mock(UserApi.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final AlertNotificationServiceImpl notifications = new AlertNotificationServiceImpl(
            writer,
            new AlertMails(new MailDeliveryProperties("no-reply@example.com", URI.create("https://app.test"))),
            sender,
            users,
            entitlements);

    @Test
    void NSF15_aTrigger_isAnAlertNotificationKeyedByTheAlertAndItsTriggerCount() {
        AlertTriggered event = fired(true, 3);

        notifications.notifyInApp(event);

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        NewNotification notification = written.getValue();
        assertThat(notification.recipientId()).isEqualTo(event.userId());
        assertThat(notification.type()).isEqualTo(NotificationType.ALERT);
        assertThat(notification.title()).isEqualTo("Price alert");
        assertThat(notification.content()).isEqualTo("BTCUSDT Spot: price crossed above 65000 (65012.5).");
        assertThat(notification.referenceType()).isEqualTo(NotificationReference.ALERT);
        assertThat(notification.referenceId()).isEqualTo(event.alertId());
        assertThat(notification.sourceKey()).isEqualTo("ALERT_TRIGGERED:" + event.alertId() + ":3");
    }

    @Test
    void NSF15_anAlertThatAsksForNoMail_isNotMailed_andNothingIsLookedUp() {
        notifications.mail(fired(false, 1));

        verifyNoInteractions(sender, users, entitlements);
    }

    @Test
    void BR62_aPlanWithoutExternalAlertChannels_isNotMailed_evenWhenTheAlertAsks() {
        AlertTriggered event = fired(true, 1);
        when(entitlements.hasFeature(event.userId(), Feature.EXTERNAL_ALERT_CHANNELS))
                .thenReturn(false);

        notifications.mail(event);

        verify(sender, never()).send(any());
        verifyNoInteractions(users);
    }

    @Test
    void NSF15_anAccountThatTakesNoNotificationMail_isNotMailed() {
        AlertTriggered event = fired(true, 1);
        when(entitlements.hasFeature(event.userId(), Feature.EXTERNAL_ALERT_CHANNELS))
                .thenReturn(true);
        when(users.notificationMailAddress(event.userId())).thenReturn(Optional.empty());

        notifications.mail(event);

        verify(sender, never()).send(any());
    }

    @Test
    void NSF15_aMailedTrigger_carriesTheNoticeWhenItFiredAndALinkToTheAlerts() {
        AlertTriggered event = fired(true, 1);
        when(entitlements.hasFeature(event.userId(), Feature.EXTERNAL_ALERT_CHANNELS))
                .thenReturn(true);
        when(users.notificationMailAddress(event.userId())).thenReturn(Optional.of(ADDRESS));

        notifications.mail(event);

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(sent.capture());
        EmailMessage mail = sent.getValue();
        assertThat(mail.to()).isEqualTo(ADDRESS);
        assertThat(mail.subject()).isEqualTo("CryptoPilot alert: BTCUSDT Spot");
        assertThat(mail.actionLink()).isEqualTo("https://app.test/alerts");
        assertThat(mail.text())
                .contains("BTCUSDT Spot: price crossed above 65000 (65012.5).")
                .contains("Triggered at 2026-10-29 03:15 UTC.")
                .contains("https://app.test/alerts")
                .contains("not investment advice");
        assertThat(mail.html())
                .contains("<a href=\"https://app.test/alerts\">")
                .contains("BTCUSDT Spot: price crossed above 65000 (65012.5).");
    }

    private static AlertTriggered fired(boolean notifyEmail, int triggerCount) {
        return new AlertTriggered(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "BTCUSDT",
                MarketType.SPOT,
                AlertType.PRICE,
                null,
                ConditionOperator.CROSS_ABOVE,
                new BigDecimal("65000.00"),
                new BigDecimal("65012.50"),
                Instant.parse("2026-10-29T03:15:00Z"),
                triggerCount,
                true,
                notifyEmail,
                false);
    }
}
