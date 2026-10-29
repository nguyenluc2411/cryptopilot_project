package com.cryptopilot.notification.model.enums;

/**
 * What a notification is about (SRS 3.4.5): the four kinds of the logical model, and the community's comments and
 * follows (SRS 3.7.3, D-99, D-125).
 *
 * <p>Rule: SRS 3.4.5, 3.7.3; D-99, D-125.
 */
public enum NotificationType {

    /** An alert fired (MSG28). */
    ALERT,

    /** A trading plan changed state. */
    PLAN,

    /** A moderator decided on a post the recipient wrote or reported (SRS 3.11.3). */
    MODERATION,

    /** A subscription or payment changed. */
    SUBSCRIPTION,

    /** Someone commented on the recipient's post (SRS 3.7.3), or replied to their comment (D-125). */
    COMMENT,

    /**
     * A fault of the system an administrator has to act on, such as a news source that keeps failing (NSF-10). The
     * logical model has no kind for a notice to administrators; V42 adds this one.
     */
    SYSTEM,

    /** Someone started following the recipient (D-125). */
    FOLLOW,

    /** Someone mentioned the recipient in a post or a comment (D-125). */
    MENTION,

    /** A margin call or a liquidation of the recipient's paper Futures positions (TR-43); V53 adds this kind. */
    FUTURES
}
