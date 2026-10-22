package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.ReportReason;
import com.cryptopilot.community.model.enums.ReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A reported post as a moderator opens it (SCR-36): the full post, whatever its status, and its open reports.
 *
 * <p>Rule: UC-43; BR-47 (Admin sees hidden and deleted posts); SRS 3.11.3.
 *
 * @param post the post
 * @param reports its OPEN and REVIEWING reports, oldest first
 */
public record ModerationCaseResponse(PostDetailResponse post, List<Report> reports) {

    public ModerationCaseResponse {
        reports = List.copyOf(reports);
    }

    /**
     * One report.
     *
     * @param id the report
     * @param reporter who filed it
     * @param reason the reason
     * @param description the description, or {@code null}
     * @param status REVIEWING once the case is opened
     * @param createdAt when it was filed
     */
    public record Report(
            UUID id,
            AuthorResponse reporter,
            ReportReason reason,
            String description,
            ReportStatus status,
            Instant createdAt) {}
}
