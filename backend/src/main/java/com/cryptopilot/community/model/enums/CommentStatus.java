package com.cryptopilot.community.model.enums;

/**
 * Where a comment stands. A comment is never removed from the table: its author deletes it by setting
 * {@link #DELETED} (BR-47); a deleted comment that has replies is still listed, without its text.
 *
 * <p>Rule: BR-47; CR-06; SRS 3.7.3.
 */
public enum CommentStatus {
    PUBLISHED,
    HIDDEN,
    DELETED
}
