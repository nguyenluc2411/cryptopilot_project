package com.cryptopilot.notification.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.notification.config.NotificationSweepProperties;
import com.cryptopilot.notification.dto.response.NotificationResponse;
import com.cryptopilot.notification.dto.response.UnreadCountResponse;
import com.cryptopilot.notification.entity.Notification;
import com.cryptopilot.notification.repository.NotificationRepository;
import com.cryptopilot.notification.service.NotificationService;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notification centre (SRS 3.4.5, UC-15). Marking read is one statement that only touches an unread row, so a
 * second click, or a click racing "mark all", leaves the first read time.
 *
 * <p>Rule: SRS 3.4.5; UC-15; NSF-17.
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final String RESOURCE = "Notification";

    private final NotificationRepository notifications;
    private final NotificationSweepProperties sweep;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID caller, boolean unreadOnly, Integer page, Integer pageSize) {
        Pageable request = Paging.of(page, pageSize);
        Page<Notification> found = unreadOnly
                ? notifications.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(caller, request)
                : notifications.findByUserIdOrderByCreatedAtDescIdDesc(caller, request);
        return PageResponse.of(found, NotificationServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID caller) {
        return new UnreadCountResponse(notifications.countByUserIdAndReadFalse(caller));
    }

    @Override
    @Transactional
    public NotificationResponse markRead(UUID caller, UUID notificationId) {
        notifications.markRead(notificationId, caller, clock.instant());
        return notifications
                .findByIdAndUserId(notificationId, caller)
                .map(NotificationServiceImpl::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, notificationId));
    }

    @Override
    @Transactional
    public UnreadCountResponse markAllRead(UUID caller) {
        notifications.markAllRead(caller, clock.instant());
        return new UnreadCountResponse(notifications.countByUserIdAndReadFalse(caller));
    }

    @Override
    @Transactional
    public int removeExpired() {
        return notifications.deleteCreatedBefore(clock.instant().minus(sweep.retention()));
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
