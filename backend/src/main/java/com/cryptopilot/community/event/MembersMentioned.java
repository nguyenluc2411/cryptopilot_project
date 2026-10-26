package com.cryptopilot.community.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A post or a comment mentions members (D-99). Published inside the transaction that stored it, for the notification
 * of each member mentioned; the members are already checked: Traders, not banned, not the author, and neither blocking
 * nor blocked by the author. Published again when the text is edited, and a member already told is not told twice.
 *
 * <p>Rule: D-99; NSF-15.
 *
 * @param postId the post, the one to open
 * @param commentId the comment that mentions them, or {@code null} when the post does
 * @param authorId who mentions them
 * @param mentionedIds the members to tell
 * @param mentionedAt when
 */
public record MembersMentioned(
        UUID postId, UUID commentId, UUID authorId, List<UUID> mentionedIds, Instant mentionedAt) {

    public MembersMentioned {
        mentionedIds = List.copyOf(mentionedIds);
    }
}
