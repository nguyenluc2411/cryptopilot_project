package com.cryptopilot.notification.dto.response;

import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;

/**
 * One notification as SCR-15 lists it: type, title, time and read status, and what opening it navigates to (SRS
 * 3.4.5). Also the body pushed on {@code /user/queue/notifications} when a notification is written.
 *
 * <p>Rule: SRS 3.4.5; UC-15.
 *
 * @param id the notification
 * @param type what it is about
 * @param title its title
 * @param content its text
 * @param referenceType what opening it navigates to, or {@code null} for nothing
 * @param referenceId the id of that object, or {@code null}
 * @param read whether it was read
 * @param readAt when it was read, or {@code null}
 * @param createdAt when it was written
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String content,
        NotificationReference referenceType,
        UUID referenceId,
        boolean read,
        Instant readAt,
        Instant createdAt) {}
