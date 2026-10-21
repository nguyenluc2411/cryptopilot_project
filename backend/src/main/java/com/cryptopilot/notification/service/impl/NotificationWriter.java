package com.cryptopilot.notification.service.impl;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.notification.dto.response.NotificationResponse;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes in-app notifications (NSF-15: "every notification is shown in-app") and pushes each new one to its
 * recipient's {@code /user/queue/notifications} once the transaction has committed, so the bell updates without a
 * reload (SCR-15).
 *
 * <p>A notification already written for its source key, or for an account that no longer exists, is not written and
 * not pushed: an event delivered twice notifies once. The push is not part of the notification: a failure is logged
 * and dropped, and the Trader sees the row on the next read. Outside a transaction nothing is pushed.
 *
 * <p>Rule: NSF-15; SRS 3.4.5; TECHNICAL_DESIGN 9.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class NotificationWriter {

    /** The user queue of each new notification. */
    static final String QUEUE = "/queue/notifications";

    private static final Logger log = LoggerFactory.getLogger(NotificationWriter.class);

    private final NotificationRepository notifications;
    private final SimpMessageSendingOperations broker;
    private final Clock clock;

    /** Writes the notification unless its source key is taken; whether it was written. */
    boolean write(NewNotification notification) {
        UUID id = UuidV7.next();
        Instant now = clock.instant();
        int written = notifications.insert(
                id,
                notification.recipientId(),
                notification.type().name(),
                notification.title(),
                notification.content(),
                notification.referenceType() == null
                        ? null
                        : notification.referenceType().name(),
                notification.referenceId(),
                notification.sourceKey(),
                now);
        if (written == 0) {
            return false;
        }
        pushAfterCommit(
                notification.recipientId(),
                new NotificationResponse(
                        id,
                        notification.type(),
                        notification.title(),
                        notification.content(),
                        notification.referenceType(),
                        notification.referenceId(),
                        false,
                        null,
                        now));
        return true;
    }

    private void pushAfterCommit(UUID recipientId, NotificationResponse body) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    broker.convertAndSendToUser(recipientId.toString(), QUEUE, body);
                } catch (RuntimeException failure) {
                    log.warn("Notification push to user {} failed: {}", recipientId, failure.toString());
                }
            }
        });
    }
}
