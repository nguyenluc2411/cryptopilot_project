package com.cryptopilot.notification.dto.response;

/**
 * The number on the bell (SRS 3.4.5).
 *
 * @param unread how many of the caller's notifications are unread
 */
public record UnreadCountResponse(long unread) {}
