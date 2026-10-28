package com.cryptopilot.community.model.enums;

/**
 * What a Trader does to a post or a comment: like it, or save a post to read later (BR-45).
 *
 * <p>Rule: BR-45; UC-31, UC-32.
 */
public enum EngagementAction {
    LIKE,
    SAVE,

    /** A signed-in member other than the author opened the post; counted once per member (D-125). */
    VIEW,

    /** A reader votes the post bullish; at most one of BULLISH and BEARISH per member (D-125). */
    BULLISH,

    /** A reader votes the post bearish (D-125). */
    BEARISH
}
