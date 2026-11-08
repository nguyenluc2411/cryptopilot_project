package com.cryptopilot.notification.job;

import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.service.impl.PaymentReceiptMails;
import com.cryptopilot.user.UserApi;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Mails the receipt of a paid order (T-069). The event arrives through the event publication registry after the
 * settlement commits, and a failed send is thrown so that the outbox processor retries it; delivery is at least once,
 * so a receipt may arrive twice. It goes out whatever the Trader's mail preference: a receipt is the record of a
 * payment, not a notification.
 *
 * <p>Rule: BR-53; NSF-15.
 */
@Component
public class PaymentReceiptMailListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentReceiptMailListener.class);

    private final PaymentReceiptMails mails;
    private final EmailSender sender;
    private final UserApi users;

    public PaymentReceiptMailListener(PaymentReceiptMails mails, EmailSender sender, UserApi users) {
        this.mails = mails;
        this.sender = sender;
        this.users = users;
    }

    // The address is read before the send; no transaction is held while the SMTP server answers.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(SubscriptionOrderPaid paid) {
        Optional<String> email = users.emailOf(paid.userId());
        if (email.isEmpty()) {
            log.warn(
                    "order {} was paid by account {}, which no longer exists; no receipt sent",
                    paid.orderCode(),
                    paid.userId());
            return;
        }
        sender.send(mails.receipt(paid, email.get()));
    }
}
