package com.cryptopilot.notification.model.enums;

/**
 * What the Push Notification Service answered for one message (NSF-15).
 *
 * <ul>
 *   <li>{@link #DELIVERED} — accepted for delivery.
 *   <li>{@link #INVALID_TOKEN} — the token is not, or no longer, an installation of the app: its device is deactivated
 *       and the message is not sent again.
 *   <li>{@link #FAILED} — not accepted for a passing reason (the service unreachable, overloaded or rate limiting,
 *       an access token to sign again): sent again, up to the retries of NSF-15.
 *   <li>{@link #NON_RETRYABLE} — refused for a reason a retry cannot change (a malformed message, a wrong project,
 *       a sender the token does not belong to): given up at once, the device kept.
 * </ul>
 *
 * <p>Reference: Google. <i>Firebase Cloud Messaging HTTP v1 API</i>, "ErrorCode".
 */
public enum PushOutcome {
    DELIVERED,
    INVALID_TOKEN,
    FAILED,
    NON_RETRYABLE
}
