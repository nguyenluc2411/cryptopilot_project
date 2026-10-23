package com.cryptopilot.community.model.enums;

/**
 * What a post carries besides its text: nothing more than images ({@link #TEXT}), or one video ({@link #VIDEO}).
 *
 * <p>Rule: UC-27, UC-28; BR-43, BR-44.
 */
public enum PostType {
    TEXT,
    VIDEO
}
