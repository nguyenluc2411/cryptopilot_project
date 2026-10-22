package com.cryptopilot.notification.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.notification.dto.response.NotificationResponse;
import com.cryptopilot.notification.dto.response.UnreadCountResponse;
import java.util.UUID;

/**
 * The notification centre of a Trader (SRS 3.4.5, UC-15): the list newest first, the unread count on the bell, and
 * marking one or all read. Another user's notification answers MSG41. Implemented by
 * {@link com.cryptopilot.notification.service.impl.NotificationServiceImpl}.
 *
 * <p>Rule: SRS 3.4.5; UC-15; NSF-17.
 */
public interface NotificationService {

    /** One page of the caller's notifications, the newest first; only the unread ones when {@code unreadOnly}. */
    PageResponse<NotificationResponse> list(UUID caller, boolean unreadOnly, Integer page, Integer pageSize);

    /** How many of the caller's notifications are unread. */
    UnreadCountResponse unreadCount(UUID caller);

    /**
     * Marks one of the caller's notifications read, as opening it does; already read stays as it was.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when it is not the caller's
     */
    NotificationResponse markRead(UUID caller, UUID notificationId);

    /** Marks all the caller's notifications read; answers the unread count, now zero. */
    UnreadCountResponse markAllRead(UUID caller);

    /** Deletes the notifications older than the retention period (NSF-17); how many. */
    int removeExpired();
}
