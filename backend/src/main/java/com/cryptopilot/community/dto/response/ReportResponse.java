package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.ReportReason;
import com.cryptopilot.community.model.enums.ReportStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A report as it was filed; the client shows MSG34 (submitted).
 *
 * @param id the report
 * @param postId the reported post
 * @param reason the reason chosen
 * @param description the description, or {@code null}
 * @param status OPEN
 * @param createdAt when it was filed
 */
public record ReportResponse(
        UUID id, UUID postId, ReportReason reason, String description, ReportStatus status, Instant createdAt) {}
