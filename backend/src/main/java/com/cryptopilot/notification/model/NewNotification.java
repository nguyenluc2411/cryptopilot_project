package com.cryptopilot.notification.model;

import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import java.util.Objects;
import java.util.UUID;

/**
 * A notification to write for one recipient.
 *
 * @param recipientId who receives it
 * @param type what it is about
 * @param title its title, at most 255 characters
 * @param content its text
 * @param referenceType what opening it navigates to, or {@code null}
 * @param referenceId the id of that object, or {@code null}
 * @param sourceKey what it was written for, unique: a second write with the same key writes nothing
 */
public record NewNotification(
        UUID recipientId,
        NotificationType type,
        String title,
        String content,
        NotificationReference referenceType,
        UUID referenceId,
        String sourceKey) {

    public NewNotification {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sourceKey, "sourceKey");
    }
}
