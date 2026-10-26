package com.cryptopilot.community.service.impl;

import com.cryptopilot.community.entity.ForumComment;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.repository.UserRelationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may see what in the community. A post follows BR-47 ({@link ForumPost#isVisibleTo(UUID)}), and a block works
 * both ways (D-121): when either of two members blocks the other, neither sees the other's posts and comments, even
 * through a direct link, and neither likes, saves, reports, comments on or replies to them. What a member may not see
 * answers MSG41, as if it did not exist, so a block is not revealed. A Guest blocks nobody.
 *
 * <p>Rule: BR-47; D-99, D-121.
 */
@Component
@RequiredArgsConstructor
class CommunityAccess {

    private final UserRelationRepository relations;

    /** Whether the caller may see the post: BR-47, and no block between the caller and its author. */
    boolean canSee(ForumPost post, UUID caller) {
        return post.isVisibleTo(caller) && !blockedWith(caller, post.getAuthorId());
    }

    /** Whether the caller may see the comment's author: no block between the two. Its post is checked apart. */
    boolean canSee(ForumComment comment, UUID caller) {
        return !blockedWith(caller, comment.getAuthorId());
    }

    /** Whether either of the caller and {@code other} blocks the other; never for a Guest or for oneself. */
    boolean blockedWith(UUID caller, UUID other) {
        return caller != null && !caller.equals(other) && relations.blockedBetween(caller, other);
    }
}
