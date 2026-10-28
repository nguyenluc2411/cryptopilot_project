package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.ReportReason;
import com.cryptopilot.community.model.enums.ReportStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A report as it was filed; the client shows MSG34 (submitted).
 *
 * @param id the report
 * @param postId the reported post, or the post of the reported comment
 * @param commentId the reported comment, or {@code null} when the post itself is reported (D-125)
 * @param reason the reason chosen
 * @param description the description, or {@code null}
 * @param status OPEN
 * @param createdAt when it was filed
 */
public record ReportResponse(
        UUID id,
        UUID postId,
        UUID commentId,
        ReportReason reason,
        String description,
        ReportStatus status,
        Instant createdAt) {}
