package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.ReportReason;
import com.cryptopilot.community.model.enums.ReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A reported comment as a moderator opens it (D-99): the comment as it is now and its open reports, each with the
 * text the comment had when it was reported.
 *
 * <p>Rule: UC-43; BR-47; D-99.
 *
 * @param comment the comment
 * @param reports its OPEN and REVIEWING reports, oldest first
 */
public record CommentModerationCaseResponse(ModeratedCommentResponse comment, List<Report> reports) {

    public CommentModerationCaseResponse {
        reports = List.copyOf(reports);
    }

    /**
     * One report.
     *
     * @param id the report
     * @param reporter who filed it
     * @param reason the reason
     * @param description the description, or {@code null}
     * @param reportedContent the comment's text when it was reported
     * @param status REVIEWING once the case is opened
     * @param createdAt when it was filed
     */
    public record Report(
            UUID id,
            AuthorResponse reporter,
            ReportReason reason,
            String description,
            String reportedContent,
            ReportStatus status,
            Instant createdAt) {}
}
