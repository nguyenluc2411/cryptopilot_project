package com.cryptopilot.notification.job;

import com.cryptopilot.billing.event.SubscriptionEndingSoon;
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
 * Mails the 3-day reminder of NSF-14. The billing module publishes it once per subscription, so the mail is sent
 * once; it goes out whatever the Trader chose for alert emails, since it is about the account, not an alert.
 *
 * <p>Rule: NSF-14.
 */
@Component
public class SubscriptionReminderMailListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionReminderMailListener.class);

    private final PaymentReceiptMails mails;
    private final EmailSender sender;
    private final UserApi users;

    public SubscriptionReminderMailListener(PaymentReceiptMails mails, EmailSender sender, UserApi users) {
        this.mails = mails;
        this.sender = sender;
        this.users = users;
    }

    // The address is read before the send; no transaction is held while the SMTP server answers.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(SubscriptionEndingSoon soon) {
        Optional<String> email = users.emailOf(soon.userId());
        if (email.isEmpty()) {
            log.warn("subscription {} ends soon but account {} no longer exists", soon.subscriptionId(), soon.userId());
            return;
        }
        sender.send(mails.endingSoon(soon, email.get()));
    }
}
