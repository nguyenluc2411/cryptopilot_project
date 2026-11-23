package com.cryptopilot.notification.model;

import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import java.util.Objects;
import java.util.UUID;

/**
 * An in-app notification was written (NSF-15). Published inside the transaction that wrote it, so the push to the
 * recipient's devices runs only once the row has committed, through the event publication registry, and is sent again
 * after a restart if it did not finish (T-058). Internal to this module.
 *
 * @param notificationId the notification
 * @param recipientId who receives it
 * @param type what it is about
 * @param title its title
 * @param content its text
 * @param referenceType what opening it navigates to, or {@code null}
 * @param referenceId the id of that object, or {@code null}
 */
public record NotificationWritten(
        UUID notificationId,
        UUID recipientId,
        NotificationType type,
        String title,
        String content,
        NotificationReference referenceType,
        UUID referenceId) {

    public NotificationWritten {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
    }
}
