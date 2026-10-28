package com.cryptopilot.community.service;

import com.cryptopilot.community.dto.response.EngagementResponse;
import com.cryptopilot.community.dto.response.VoteResponse;
import com.cryptopilot.community.model.enums.EngagementAction;
import com.cryptopilot.community.model.enums.PostSentiment;
import java.util.UUID;

/**
 * Likes of posts and comments and saves of posts (UC-31, UC-32). Each is at most once per Trader and target, and
 * repeating a click changes nothing (BR-45). The target must be one the caller may see (BR-47); MSG41 otherwise.
 * Implemented by {@link com.cryptopilot.community.service.impl.EngagementServiceImpl}.
 *
 * <p>Rule: UC-31, UC-32; BR-45, BR-47; D-48.
 */
public interface EngagementService {

    /** Likes or saves a post, or takes the like or save back. */
    EngagementResponse onPost(UUID caller, UUID postId, EngagementAction action, boolean active);

    /** Votes a post Bullish or Bearish, replacing an earlier vote, or takes the vote back with {@code null} (D-125). */
    VoteResponse vote(UUID caller, UUID postId, PostSentiment sentiment);

    /** Likes a comment, or takes the like back. */
    EngagementResponse likeComment(UUID caller, UUID commentId, boolean active);
}
