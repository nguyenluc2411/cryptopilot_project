package com.cryptopilot.notification.repository;

import com.cryptopilot.notification.entity.Notification;
import com.cryptopilot.notification.model.enums.NotificationType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notifications of each user (SRS 3.4.5). Writing one and marking one or all read are single statements, so a
 * repeated delivery or a double click changes nothing the second time.
 *
 * <p>Rule: SRS 3.4.5; UC-15; NSF-15, NSF-17.
 */
public interface NotificationRepository extends Repository<Notification, UUID> {

    /**
     * Writes a notification unless one was already written for {@code sourceKey}, or the recipient's account no
     * longer exists; 1 when a row was added.
     */
    @Modifying
    @Query(value = """
                    insert into notification (notification_id, user_id, notification_type, title, content,
                                              reference_type, reference_id, source_key, created_at, updated_at)
                    select :id, :user, :type, :title, :content, :referenceType, :referenceId, :sourceKey, :now, :now
                     where exists (select 1 from user_account where user_id = :user)
                    on conflict (source_key) do nothing""", nativeQuery = true)
    int insert(
            @Param("id") UUID id,
            @Param("user") UUID user,
            @Param("type") String type,
            @Param("title") String title,
            @Param("content") String content,
            @Param("referenceType") String referenceType,
            @Param("referenceId") UUID referenceId,
            @Param("sourceKey") String sourceKey,
            @Param("now") Instant now);

    /** Marks one of the user's notifications read; 0 when it is not theirs or was read already. */
    @Modifying
    @Query(value = """
                    update notification
                       set is_read = true, read_at = :now, updated_at = :now, version = version + 1
                     where notification_id = :id and user_id = :user and not is_read""", nativeQuery = true)
    int markRead(@Param("id") UUID id, @Param("user") UUID user, @Param("now") Instant now);

    /** Marks every unread notification of the user read; how many were. */
    @Modifying
    @Query(value = """
                    update notification
                       set is_read = true, read_at = :now, updated_at = :now, version = version + 1
                     where user_id = :user and not is_read""", nativeQuery = true)
    int markAllRead(@Param("user") UUID user, @Param("now") Instant now);

    /** Deletes the notifications created before {@code cutoff} (NSF-17); how many. */
    @Modifying
    @Query(value = "delete from notification where created_at < :cutoff", nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

    @Transactional(readOnly = true)
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Transactional(readOnly = true)
    Optional<Notification> findBySourceKey(String sourceKey);

    /** One page of the user's notifications, the newest first. */
    @Transactional(readOnly = true)
    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable page);

    /** One page of the user's unread notifications, the newest first. */
    @Transactional(readOnly = true)
    Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(UUID userId, Pageable page);

    /** Whether the user has an unread notification of this type about this object. */
    @Transactional(readOnly = true)
    boolean existsByUserIdAndTypeAndReferenceIdAndReadFalse(UUID userId, NotificationType type, UUID referenceId);

    /** How many of the user's notifications are unread: the number on the bell. */
    @Transactional(readOnly = true)
    long countByUserIdAndReadFalse(UUID userId);
}
