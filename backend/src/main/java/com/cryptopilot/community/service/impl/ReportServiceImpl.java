package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.community.dto.request.ReportRequest;
import com.cryptopilot.community.dto.response.ReportResponse;
import com.cryptopilot.community.entity.ForumComment;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.entity.PostReport;
import com.cryptopilot.community.model.enums.ReportReason;
import com.cryptopilot.community.repository.ForumCommentRepository;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostReportRepository;
import com.cryptopilot.community.service.ReportService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Files violation reports (UC-33). A Trader reports a post once and never their own (BR-46): the check answers the
 * usual case, and {@code uq_post_report_post_reporter} answers two reports sent at the same moment the same way. Only
 * posts are reported, not comments (D-14).
 *
 * <p>Rule: UC-33; BR-46, BR-47; SRS 3.7.4; MSG34; D-14.
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ForumPostRepository posts;
    private final PostReportRepository reports;
    private final ForumCommentRepository comments;
    private final CommunityAccess access;

    @Override
    @Transactional
    public ReportResponse report(UUID caller, UUID postId, ReportRequest request) {
        ForumPost post = posts.findById(postId)
                .filter(found -> access.canSee(found, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
        if (post.getAuthorId().equals(caller)) {
            throw new BusinessException(ErrorCode.POST_REPORT_OWN_POST, "a Trader cannot report their own post");
        }
        requireDescriptionForOther(request);
        if (reports.existsByPostIdAndCommentIdIsNullAndReporterId(post.getId(), caller)) {
            throw alreadyReported(post.getId());
        }
        PostReport report = PostReport.file(post.getId(), caller, request.reason(), request.description());
        try {
            reports.save(report);
            reports.flush();
        } catch (DataIntegrityViolationException raced) {
            // Only uq_post_report_post_reporter can refuse this insert; the post and the reporter exist.
            throw alreadyReported(post.getId());
        }
        return response(report);
    }

    @Override
    @Transactional
    public ReportResponse reportComment(UUID caller, UUID commentId, ReportRequest request) {
        ForumComment comment = comments.findById(commentId)
                .filter(ForumComment::isPublished)
                .filter(found -> access.canSee(found, caller))
                .filter(found -> posts.findById(found.getPostId())
                        .filter(post -> access.canSee(post, caller))
                        .isPresent())
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        if (comment.getAuthorId().equals(caller)) {
            throw new BusinessException(
                    ErrorCode.COMMENT_REPORT_OWN_COMMENT, "a Trader cannot report their own comment");
        }
        requireDescriptionForOther(request);
        if (reports.existsByCommentIdAndReporterId(commentId, caller)) {
            throw commentAlreadyReported(commentId);
        }
        PostReport report = PostReport.fileOnComment(comment, caller, request.reason(), request.description());
        try {
            reports.save(report);
            reports.flush();
        } catch (DataIntegrityViolationException raced) {
            // Only uq_post_report_comment_reporter can refuse this insert; the comment and the reporter exist.
            throw commentAlreadyReported(commentId);
        }
        return response(report);
    }

    private static void requireDescriptionForOther(ReportRequest request) {
        if (request.reason() == ReportReason.OTHER && request.description() == null) {
            throw new FieldValidationException(
                    "a report for another reason needs a description", Map.of("description", "MSG01"));
        }
    }

    private static ReportResponse response(PostReport report) {
        return new ReportResponse(
                report.getId(),
                report.getPostId(),
                report.getCommentId(),
                report.getReason(),
                report.getDescription(),
                report.getStatus(),
                report.getCreatedAt());
    }

    private static BusinessException commentAlreadyReported(UUID commentId) {
        return new BusinessException(
                ErrorCode.COMMENT_ALREADY_REPORTED, "comment " + commentId + " was already reported");
    }

    private static BusinessException alreadyReported(UUID postId) {
        return new BusinessException(ErrorCode.POST_ALREADY_REPORTED, "post " + postId + " was already reported");
    }
}
