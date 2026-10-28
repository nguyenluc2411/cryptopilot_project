package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.ReportReason;
import java.time.Instant;
import java.util.List;

/**
 * One row of the comment moderation queue (D-125): the reported comment and its open reports gathered.
 *
 * <p>Rule: UC-43; D-125.
 *
 * @param comment the comment, whatever its status
 * @param reportCount its OPEN and REVIEWING reports
 * @param reasons their distinct reasons
 * @param firstReportedAt when the oldest was filed
 * @param lastReportedAt when the newest was filed
 */
public record CommentQueueItemResponse(
        ModeratedCommentResponse comment,
        long reportCount,
        List<ReportReason> reasons,
        Instant firstReportedAt,
        Instant lastReportedAt) {}
