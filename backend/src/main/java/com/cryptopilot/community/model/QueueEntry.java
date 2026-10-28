package com.cryptopilot.community.model;

import com.cryptopilot.community.model.enums.ReportReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One post of the moderation queue: its open reports gathered together (SCR-36).
 *
 * @param targetId the reported post, or the reported comment in the comment queue (D-125)
 * @param reportCount how many OPEN or REVIEWING reports it has
 * @param reasons the distinct reasons of those reports, in the order of {@link ReportReason}
 * @param firstReportedAt when the oldest of them was filed
 * @param lastReportedAt when the newest was filed
 */
public record QueueEntry(
        UUID targetId, long reportCount, List<ReportReason> reasons, Instant firstReportedAt, Instant lastReportedAt) {

    public QueueEntry {
        reasons = List.copyOf(reasons);
    }
}
