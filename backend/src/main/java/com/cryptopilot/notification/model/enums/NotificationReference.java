package com.cryptopilot.notification.model.enums;

/**
 * What opening a notification navigates to (SRS 3.4.5: "the referenced alert, plan, post or subscription"). Stored in
 * {@code reference_type}; the id is in {@code reference_id}.
 *
 * <p>Rule: SRS 3.4.5.
 */
public enum NotificationReference {
    ALERT,
    PLAN,
    POST,
    SUBSCRIPTION,

    /** A news source of the administration console (SCR-41), for a source that keeps failing (NSF-10). */
    NEWS_SOURCE
}
