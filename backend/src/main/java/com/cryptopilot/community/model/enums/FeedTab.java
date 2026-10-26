package com.cryptopilot.community.model.enums;

/**
 * The tabs of the community feed (SCR-24).
 *
 * <p>Rule: UC-26; SRS 3.7.1.
 */
public enum FeedTab {

    /** Published posts, newest first. */
    LATEST,

    /**
     * For You: the published posts of the last seven days, the most discussed of late first (D-99). Likes, votes,
     * comments and views raise a post; age lowers it.
     */
    TRENDING,

    /** Published posts of the last seven days, most liked first. */
    MOST_LIKED,

    /** The posts the caller saved, most recently saved first. */
    SAVED,

    /** The caller's own posts, hidden ones included, newest first. */
    MINE,

    /** The published posts of the members the caller follows, newest first (D-99). */
    FOLLOWING
}
