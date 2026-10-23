package com.cryptopilot.notification.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * A message to one user, shown in the notification centre (SRS 3.4.5, UC-15) with its read status. Written and marked
 * read by {@link com.cryptopilot.notification.repository.NotificationRepository} with single statements — an insert
 * that a repeated delivery of the same event leaves alone ({@code uq_notification_source_key}), and updates that a
 * second click leaves as they are — so this entity is read only.
 *
 * <p>Rule: SRS 3.4.5; UC-15; NSF-15.
 */
@Getter
@Entity
@Table(name = "notification")
@AttributeOverride(name = "id", column = @Column(name = "notification_id", nullable = false, updatable = false))
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The alert that fired, for an ALERT notification; {@code null} once the alert is deleted. */
    @Column(name = "alert_id", updatable = false)
    private UUID alertId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 32, updatable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 64, updatable = false)
    private NotificationReference referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "sent_via_push", nullable = false)
    private boolean sentViaPush;

    @Column(name = "sent_via_email", nullable = false)
    private boolean sentViaEmail;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "source_key", length = 128, updatable = false)
    private String sourceKey;

    /** For JPA only. */
    protected Notification() {}
}
