package com.cryptopilot.notification.model.enums;

/**
 * What a notification is about (SRS 3.4.5): the four kinds of the logical model, and the community's comments and
 * follows (SRS 3.7.3, D-99).
 *
 * <p>Rule: SRS 3.4.5, 3.7.3; D-99.
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

    /** Someone commented on the recipient's post (SRS 3.7.3). */
    COMMENT,

    /** Someone started following the recipient (D-99). */
    FOLLOW
}
