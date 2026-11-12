package com.cryptopilot.notification.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import com.cryptopilot.notification.service.impl.PaymentReceiptMails;
import com.cryptopilot.user.UserApi;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The NSF-14 reminder goes to the account's address, and to nobody once the account is gone. */
class SubscriptionReminderMailListenerTest {

    private static final UUID USER = UUID.fromString("01a14a40-703a-74e7-bb70-8c0b6189358f");

    private final EmailSender sender = mock(EmailSender.class);
    private final UserApi users = mock(UserApi.class);
    private final SubscriptionReminderMailListener listener = new SubscriptionReminderMailListener(
            new PaymentReceiptMails(new MailDeliveryProperties("no-reply@example.com", URI.create("https://app.test"))),
            sender,
            users);

    private static final SubscriptionEndingSoon SOON =
            new SubscriptionEndingSoon(UUID.randomUUID(), USER, "Pro Monthly", Instant.parse("2026-12-08T03:00:00Z"));

    @Test
    void NSF14_theReminder_isMailedToTheAccountsAddress() {
        when(users.emailOf(USER)).thenReturn(Optional.of("jane@example.com"));

        listener.on(SOON);

        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("jane@example.com");
        assertThat(mail.getValue().subject()).contains("Pro Monthly");
    }

    @Test
    void anAccountThatNoLongerExists_isSentNothing() {
        when(users.emailOf(USER)).thenReturn(Optional.empty());

        listener.on(SOON);

        verify(sender, never()).send(any());
    }
}
