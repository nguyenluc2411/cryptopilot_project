package com.cryptopilot.community.model.enums;

/**
 * What one member chose about another (D-125). Stored in {@code user_relation.relation_type}; one per direction, so a
 * member either follows or blocks another, never both.
 *
 * <p>Rule: D-125.
 */
public enum RelationType {

    /** Reads the other's posts in the Following tab. */
    FOLLOW,

    /** No longer sees the other's posts and comments; the other can no longer follow, comment or reply to them. */
    BLOCK
}
