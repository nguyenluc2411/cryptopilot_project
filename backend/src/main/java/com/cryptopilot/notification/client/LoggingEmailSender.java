package com.cryptopilot.notification.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in for SMTP where no server is configured (T-057): logs the recipient and the link, so a developer or the FE
 * team can open it. {@code MailConfig} never selects it in the prod profile, because the line holds a working token.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.warn(
                "SMTP_HOST not set, mail not sent: to={} subject='{}' link={}",
                message.to(),
                message.subject(),
                message.actionLink());
    }
}
