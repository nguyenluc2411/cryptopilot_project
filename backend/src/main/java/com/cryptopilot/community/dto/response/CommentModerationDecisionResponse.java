package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.CommentStatus;
import com.cryptopilot.community.model.enums.ResolutionAction;
import java.util.UUID;

/**
 * What a moderation decision on a comment did (D-125).
 *
 * @param commentId the comment
 * @param action the decision
 * @param commentStatus the comment's status after it
 * @param resolvedReports how many reports it resolved
 * @param authorBanned whether it banned the author now; {@code false} for an author banned before
 */
public record CommentModerationDecisionResponse(
        UUID commentId,
        ResolutionAction action,
        CommentStatus commentStatus,
        int resolvedReports,
        boolean authorBanned) {}
