package com.cryptopilot.community.model.enums;

/**
 * What a moderator decided for the reports of a post (UC-43) or of a comment (D-125). HIDE_POST and REMOVE_POST
 * decide a post's reports, HIDE_COMMENT and REMOVE_COMMENT a comment's, the others either
 * ({@code ck_post_report_action_fits_target}).
 *
 * <p>Rule: UC-43; SRS 3.11.3; D-99, D-125.
 */
public enum ResolutionAction {

    /** The post is hidden. */
    HIDE_POST,

    /** The post is removed: deleted for everyone but Admin (D-99, "hide or remove"). */
    REMOVE_POST,

    /** The comment is hidden (D-125). */
    HIDE_COMMENT,

    /** The comment is removed: deleted for everyone but Admin (D-125). */
    REMOVE_COMMENT,

    /** The reports are dismissed; the post or comment stays. */
    DISMISS,

    /** The author is warned; the post or comment stays. */
    WARN_USER,

    /** The author is banned and the post or comment hidden. */
    BAN_USER;

    /** Whether this decides the reports of a comment only. */
    public boolean commentOnly() {
        return this == HIDE_COMMENT || this == REMOVE_COMMENT;
    }

    /** Whether this decides the reports of a post only. */
    public boolean postOnly() {
        return this == HIDE_POST || this == REMOVE_POST;
    }
}
