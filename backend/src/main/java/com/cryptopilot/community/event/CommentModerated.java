package com.cryptopilot.community.event;

import com.cryptopilot.community.model.enums.ResolutionAction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A moderator decided the open reports of a comment (D-125): the comment's author and each reporter are told, as for a
 * post (SRS 3.11.3). Published inside the decision's transaction and delivered after it commits (D-98).
 *
 * <p>Rule: UC-43; SRS 3.11.3; D-98, D-125.
 *
 * @param commentId the comment
 * @param postId its post, the one to open
 * @param authorId who wrote the comment
 * @param action the decision
 * @param reporterIds who reported it
 * @param moderatorId who decided
 * @param note the moderator's note, or {@code null}
 * @param decidedAt when
 */
public record CommentModerated(
        UUID commentId,
        UUID postId,
        UUID authorId,
        ResolutionAction action,
        List<UUID> reporterIds,
        UUID moderatorId,
        String note,
        Instant decidedAt) {

    public CommentModerated {
        reporterIds = List.copyOf(reporterIds);
    }
}
