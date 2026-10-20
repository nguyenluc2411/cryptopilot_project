package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.ReportReason;
import java.time.Instant;
import java.util.List;

/**
 * One row of the moderation queue (SCR-36): the reported post's card and its open reports gathered.
 *
 * <p>Rule: UC-43; SRS 3.11.3.
 *
 * @param post the post's card, whatever its status
 * @param reportCount its OPEN and REVIEWING reports
 * @param reasons their distinct reasons
 * @param firstReportedAt when the oldest was filed
 * @param lastReportedAt when the newest was filed
 */
public record QueueItemResponse(
        PostSummaryResponse post,
        long reportCount,
        List<ReportReason> reasons,
        Instant firstReportedAt,
        Instant lastReportedAt) {}
