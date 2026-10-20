package com.cryptopilot.community.model.enums;

/**
 * What a moderator decided for the reports of a post (UC-43).
 *
 * <p>Rule: UC-43; SRS 3.11.3.
 */
public enum ResolutionAction {

    /** The post is hidden. */
    HIDE_POST,

    /** The post is removed: deleted for everyone but Admin (D-99, "hide or remove"). */
    REMOVE_POST,

    /** The reports are dismissed; the post stays. */
    DISMISS,

    /** The author is warned; the post stays. */
    WARN_USER,

    /** The author is banned and the post hidden. */
    BAN_USER
}
