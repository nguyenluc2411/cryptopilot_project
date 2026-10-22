package com.cryptopilot.community.model.enums;

/**
 * Where a post stands. A post is never removed from the table: deleting it is {@link #DELETED} (BR-47), and a
 * moderator hides it with {@link #HIDDEN} (UC-43).
 *
 * <p>Rule: BR-47; CR-06; UC-43.
 */
public enum PostStatus {

    /** Shown to every Trader. */
    PUBLISHED,

    /** Hidden by a moderator: shown to its author and to Admin only. */
    HIDDEN,

    /** Deleted by its author: shown to Admin only. */
    DELETED
}
