package com.cryptopilot.notification.job;

import com.cryptopilot.auth.event.PasswordResetTokenIssued;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.service.impl.AccountLinkMails;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Mails the links that auth issues (T-057). Each event reaches this class through the event publication registry
 * (T-056): recorded in the transaction that wrote the token, delivered asynchronously after it commits, so a rolled
 * back registration mails nothing and a failed send is retried by the outbox processor. Orchestration only: the mail
 * is written by {@link AccountLinkMails} and sent by {@link EmailSender}, whose exceptions are left to propagate.
 *
 * <p>Delivery is at least once, so a link may arrive twice; both copies carry the same token. A link already expired
 * when its retry comes round is not sent.
 *
 * <p>Rule: BR-01, BR-04; NSF-15; SRS UC-01, UC-04.
 *
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 3 (Transactional Outbox).
 * <p>Reference: Spring Modulith Reference Documentation 2.1, Event Publication Registry.
 */
@Component
public class AccountLinkMailListener {

    private static final Logger log = LoggerFactory.getLogger(AccountLinkMailListener.class);

    private final AccountLinkMails mails;
    private final EmailSender sender;
    private final Clock clock;

    public AccountLinkMailListener(AccountLinkMails mails, EmailSender sender, Clock clock) {
        this.mails = mails;
        this.sender = sender;
        this.clock = clock;
    }

    // No database work, so no transaction: no pooled connection is held while the SMTP server answers.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(VerificationTokenIssued issued) {
        if (stillValid(issued.userId(), issued.expiresAt())) {
            sender.send(mails.verification(issued));
        }
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(PasswordResetTokenIssued issued) {
        if (stillValid(issued.userId(), issued.expiresAt())) {
            sender.send(mails.passwordReset(issued));
        }
    }

    private boolean stillValid(UUID userId, Instant expiresAt) {
        if (clock.instant().isBefore(expiresAt)) {
            return true;
        }
        log.info("Link for account {} expired at {} before it could be mailed; not sent", userId, expiresAt);
        return false;
    }
}
