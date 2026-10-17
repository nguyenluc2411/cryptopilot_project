package com.cryptopilot.notification.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cryptopilot.auth.event.PasswordResetTokenIssued;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import com.cryptopilot.notification.service.impl.AccountLinkMails;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

/**
 * The listener of T-057: a valid link is mailed, a failure propagates so that the publication stays unfinished, and a
 * link that expired before a retry came round is not sent.
 *
 * <p>Rule: BR-01, BR-04; NSF-15.
 */
class AccountLinkMailListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private static final UUID USER = UUID.fromString("01a14a40-703a-74e7-bb70-8c0b6189358f");

    private final EmailSender sender = mock(EmailSender.class);

    private final AccountLinkMailListener listener = new AccountLinkMailListener(
            new AccountLinkMails(new MailDeliveryProperties("no-reply@example.com", URI.create("https://app.test"))),
            sender,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void BR01_aValidVerificationLink_isMailed() {
        listener.on(new VerificationTokenIssued(USER, "jane@example.com", "tok", NOW.plusSeconds(1)));

        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().actionLink()).isEqualTo("https://app.test/verify-email?token=tok");
    }

    @Test
    void BR04_aValidResetLink_isMailed() {
        listener.on(new PasswordResetTokenIssued(USER, "jane@example.com", "tok", NOW.plusSeconds(1)));

        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().actionLink()).isEqualTo("https://app.test/reset-password?token=tok");
    }

    @Test
    void BR01_aLinkExpiredBeforeItsRetry_isNotSent() {
        listener.on(new VerificationTokenIssued(USER, "jane@example.com", "tok", NOW));
        listener.on(new PasswordResetTokenIssued(USER, "jane@example.com", "tok", NOW.minusSeconds(1)));

        verifyNoInteractions(sender);
    }

    @Test
    void NSF15_aFailedSend_propagates() {
        doThrow(new MailSendException("down")).when(sender).send(any());

        assertThatExceptionOfType(MailSendException.class)
                .isThrownBy(() -> listener.on(
                        new PasswordResetTokenIssued(USER, "jane@example.com", "tok", NOW.plusSeconds(60))));
    }
}
