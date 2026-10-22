package com.cryptopilot.community.event;

import com.cryptopilot.community.model.enums.ResolutionAction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A moderator decided the reports of a post (UC-43). SRS 3.11.3 tells the post's author and the reporters; the
 * in-app notifications (type MODERATION) are created by the notification task that listens to this event, which is
 * published inside the decision's transaction and delivered after it commits (D-98).
 *
 * <p>Rule: UC-43; SRS 3.11.3; NSF-15; D-98.
 *
 * @param postId the post
 * @param authorId who wrote it
 * @param action what was decided
 * @param reporterIds who reported it, each once
 * @param moderatorId who decided
 * @param note the moderator's note, or {@code null}
 * @param decidedAt when it was decided
 */
public record PostModerated(
        UUID postId,
        UUID authorId,
        ResolutionAction action,
        List<UUID> reporterIds,
        UUID moderatorId,
        String note,
        Instant decidedAt) {

    public PostModerated {
        reporterIds = List.copyOf(reporterIds);
    }
}
