package com.cryptopilot.notification.client;

import com.cryptopilot.notification.model.enums.PushOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in for Firebase Cloud Messaging where no service account is configured (T-058): logs the title and the data
 * of the message, never its token, and answers {@link PushOutcome#DELIVERED}, so development and the tests run the
 * whole dispatch without a Firebase project.
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public PushResult send(PushMessage message) {
        log.info("FCM not configured, push not sent: title='{}' data={}", message.title(), message.data());
        return PushResult.of(PushOutcome.DELIVERED);
    }
}
