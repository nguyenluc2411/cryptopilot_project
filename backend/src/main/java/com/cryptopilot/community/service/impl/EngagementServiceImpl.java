package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.entity.ForumComment;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.model.enums.EngagementAction;
import com.cryptopilot.community.repository.EngagementRepository;
import com.cryptopilot.community.repository.ForumCommentRepository;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.service.EngagementService;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Likes and saves (BR-45). Turning one on or off is a single idempotent statement, so a repeated click or two at once
 * leave one row or none; the answer is the state after the click and the new count. A comment is liked only while it
 * is published, on a post the caller may see (BR-47).
 *
 * <p>Rule: UC-31, UC-32; BR-45, BR-47.
 */
@Service
@RequiredArgsConstructor
public class EngagementServiceImpl implements EngagementService {

    private final EngagementRepository engagements;
    private final ForumPostRepository posts;
    private final ForumCommentRepository comments;
    private final Clock clock;

    @Override
    @Transactional
    public EngagementResponse onPost(UUID caller, UUID postId, EngagementAction action, boolean active) {
        ForumPost post = posts.findById(postId)
                .filter(found -> found.isVisibleTo(caller))
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
        if (active) {
            engagements.addToPost(UuidV7.next(), caller, post.getId(), action.name(), clock.instant());
        } else {
            engagements.removeFromPost(caller, post.getId(), action.name());
        }
        return new EngagementResponse(active, engagements.countByPostIdAndActionType(post.getId(), action));
    }

    @Override
    @Transactional
    public EngagementResponse likeComment(UUID caller, UUID commentId, boolean active) {
        ForumComment comment = comments.findById(commentId)
                .filter(ForumComment::isPublished)
                .filter(found -> posts.findById(found.getPostId())
                        .filter(post -> post.isVisibleTo(caller))
                        .isPresent())
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        String like = EngagementAction.LIKE.name();
        if (active) {
            engagements.addToComment(UuidV7.next(), caller, comment.getId(), like, clock.instant());
        } else {
            engagements.removeFromComment(caller, comment.getId(), like);
        }
        return new EngagementResponse(
                active, engagements.countByCommentIdAndActionType(comment.getId(), EngagementAction.LIKE));
    }
}
