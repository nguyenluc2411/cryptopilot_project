package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.ReportReason;
import com.cryptopilot.community.model.enums.ReportStatus;
import com.cryptopilot.community.model.enums.ResolutionAction;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A Trader's report that a post, or one comment of it, breaks the community rules (UC-33; comments since D-125, which
 * widens D-14). A Trader reports a post once ({@code uq_post_report_post_reporter}) and each comment once
 * ({@code uq_post_report_comment_reporter}), never their own (BR-46). A report is filed {@link ReportStatus#OPEN}; the
 * moderation queue reviews and resolves it (UC-43).
 *
 * <p>Rule: UC-33; BR-46; D-14, D-99, D-125; SRS 3.7.4.
 */
@Getter
@Entity
@Table(name = "post_report")
@AttributeOverride(name = "id", column = @Column(name = "report_id", nullable = false, updatable = false))
public class PostReport extends BaseEntity {

    /** Longest description, in characters (SRS 3.7.4). */
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    /** The reported comment of the post, or {@code null} when the post itself is reported (D-125). */
    @Column(name = "comment_id", updatable = false)
    private UUID commentId;

    /** The comment's text when it was reported, kept for the moderator whatever its author edits; {@code null} for a post. */
    @Column(name = "reported_content", updatable = false)
    private String reportedContent;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32, updatable = false)
    private ReportReason reason;

    @Column(name = "description", updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_status", nullable = false, length = 32)
    private ReportStatus status;

    /** The moderator who resolved it, or {@code null} until then. */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_action", length = 32)
    private ResolutionAction resolutionAction;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** For JPA only. */
    protected PostReport() {}

    private PostReport(UUID postId, UUID reporterId, ReportReason reason, String description) {
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.reporterId = Objects.requireNonNull(reporterId, "reporterId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.description = description == null || description.isBlank() ? null : description.strip();
        if (reason == ReportReason.OTHER && this.description == null) {
            throw new IllegalArgumentException("a report for another reason describes it");
        }
        if (this.description != null && this.description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a report's description has at most " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        this.status = ReportStatus.OPEN;
    }

    /** Marks an OPEN report as being looked at by a moderator (UC-43); any other report is left as it is. */
    public void review() {
        if (status == ReportStatus.OPEN) {
            this.status = ReportStatus.REVIEWING;
        }
    }

    /** Records the moderator's decision on a report that is not resolved yet (UC-43). */
    public void resolve(UUID moderatorId, ResolutionAction action, Instant at) {
        if (status == ReportStatus.RESOLVED) {
            throw new IllegalStateException("report " + getId() + " is resolved already");
        }
        this.resolvedBy = Objects.requireNonNull(moderatorId, "moderatorId must not be null");
        this.resolutionAction = Objects.requireNonNull(action, "action must not be null");
        if (isOfComment() ? action.postOnly() : action.commentOnly()) {
            throw new IllegalArgumentException(
                    action + " does not decide a report of a " + (isOfComment() ? "comment" : "post"));
        }
        this.resolvedAt = Objects.requireNonNull(at, "at must not be null");
        this.status = ReportStatus.RESOLVED;
    }

    /** Files an OPEN report of a post; {@code description} is required for {@link ReportReason#OTHER}. */
    public static PostReport file(UUID postId, UUID reporterId, ReportReason reason, String description) {
        return new PostReport(postId, reporterId, reason, description);
    }

    /** Files an OPEN report of a comment, keeping its text as it is now (D-125). */
    public static PostReport fileOnComment(
            ForumComment comment, UUID reporterId, ReportReason reason, String description) {
        PostReport report = new PostReport(comment.getPostId(), reporterId, reason, description);
        report.commentId = comment.getId();
        report.reportedContent = comment.getContent();
        return report;
    }

    /** Whether this reports a comment rather than the post itself. */
    public boolean isOfComment() {
        return commentId != null;
    }
}
