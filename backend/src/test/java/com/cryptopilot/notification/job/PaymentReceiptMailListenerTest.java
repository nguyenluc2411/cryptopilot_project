package com.cryptopilot.notification.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import com.cryptopilot.notification.service.impl.PaymentReceiptMails;
import com.cryptopilot.user.UserApi;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

/**
 * The receipt listener: the receipt goes to the account's address, a failed send propagates so that the publication
 * stays unfinished and is retried, and an account that no longer exists gets nothing.
 *
 * <p>Rule: BR-53; NSF-15.
 */
class PaymentReceiptMailListenerTest {

    private static final UUID USER = UUID.fromString("01a14a40-703a-74e7-bb70-8c0b6189358f");

    private final EmailSender sender = mock(EmailSender.class);
    private final UserApi users = mock(UserApi.class);
    private final PaymentReceiptMailListener listener = new PaymentReceiptMailListener(
            new PaymentReceiptMails(new MailDeliveryProperties("no-reply@example.com", URI.create("https://app.test"))),
            sender,
            users);

    @Test
    void BR53_aPaidOrder_isReceiptedToTheAccountsAddress() {
        when(users.emailOf(USER)).thenReturn(Optional.of("jane@example.com"));

        listener.on(paid());

        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("jane@example.com");
        assertThat(mail.getValue().subject()).contains("CP1");
    }

    @Test
    void NSF15_aFailedSend_propagatesSoThatItIsRetried() {
        when(users.emailOf(USER)).thenReturn(Optional.of("jane@example.com"));
        doThrow(new MailSendException("down")).when(sender).send(any());

        assertThatThrownBy(() -> listener.on(paid())).isInstanceOf(MailSendException.class);
    }

    @Test
    void anAccountThatNoLongerExists_isSentNothing() {
        when(users.emailOf(USER)).thenReturn(Optional.empty());

        listener.on(paid());

        verify(sender, never()).send(any());
    }

    private static SubscriptionOrderPaid paid() {
        Instant at = Instant.parse("2026-11-08T07:59:19Z");
        return new SubscriptionOrderPaid(
                UUID.randomUUID(),
                USER,
                "CP1",
                "Pro Monthly",
                "NEW",
                new BigDecimal("99000"),
                "VND",
                "SEPAY",
                "TX-1",
                at,
                at,
                at.plusSeconds(30L * 86_400),
                BigDecimal.ZERO,
                0);
    }
}
