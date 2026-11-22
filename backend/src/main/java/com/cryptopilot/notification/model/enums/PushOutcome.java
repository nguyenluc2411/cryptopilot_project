package com.cryptopilot.notification.model.enums;

/**
 * What the Push Notification Service answered for one message (NSF-15).
 *
 * <ul>
 *   <li>{@link #DELIVERED} — accepted for delivery.
 *   <li>{@link #INVALID_TOKEN} — the token is not, or no longer, an installation of the app: its device is deactivated
 *       and the message is not sent again.
 *   <li>{@link #FAILED} — not accepted for another reason (the service unreachable, overloaded, refusing our
 *       credentials): sent again, up to the retries of NSF-15.
 * </ul>
 */
public enum PushOutcome {
    DELIVERED,
    INVALID_TOKEN,
    FAILED
}
