package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.community.model.enums.ResolutionAction;
import java.util.UUID;

/**
 * What a moderation decision did (UC-43).
 *
 * @param postId the post
 * @param action the decision
 * @param postStatus the post's status after it
 * @param resolvedReports how many reports it resolved
 * @param authorBanned whether it banned the author now; {@code false} for an author banned before
 */
public record ModerationDecisionResponse(
        UUID postId, ResolutionAction action, PostStatus postStatus, int resolvedReports, boolean authorBanned) {}
