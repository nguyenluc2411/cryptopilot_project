package com.cryptopilot.community.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.ModerationDecisionRequest;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.CommentModerationCaseResponse;
import com.cryptopilot.community.dto.response.CommentModerationDecisionResponse;
import com.cryptopilot.community.dto.response.CommentQueueItemResponse;
import com.cryptopilot.community.dto.response.ModeratedCommentResponse;
import com.cryptopilot.community.entity.ForumComment;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.entity.PostReport;
import com.cryptopilot.community.event.CommentModerated;
import com.cryptopilot.community.model.QueueEntry;
import com.cryptopilot.community.model.enums.CommentStatus;
import com.cryptopilot.community.model.enums.ReportStatus;
import com.cryptopilot.community.model.enums.ResolutionAction;
import com.cryptopilot.community.repository.ForumCommentRepository;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.ModerationQueueRepository;
import com.cryptopilot.community.repository.PostReportRepository;
import com.cryptopilot.community.service.CommentModerationService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The moderation of reported comments (D-125), shaped like that of posts. All open reports of a comment are decided
 * together: HIDE_COMMENT hides it (restorable), REMOVE_COMMENT deletes it for everyone but Admin, BAN_USER hides it and
 * bans its author, which ends every session of the author (BR-06) and, through the ban, hides the rest of their
 * content; WARN_USER and DISMISS leave it as it is. The decision and its effects are written to the audit log in the
 * same transaction (BR-57), and {@link CommentModerated} tells the author and the reporters after it commits.
 *
 * <p>A ban hides the author's other content after it commits (D-98). A decision on another of their comments taken
 * while that runs may lose the comment's optimistic lock and answer {@code DATA_CONFLICT} (409); nothing is changed,
 * and deciding again succeeds. This is accepted rather than locked against, as two moderators deciding the same
 * comment are answered the same way.
 *
 * <p>Rule: UC-43; BR-06, BR-57, BR-58; SRS 3.11.3; D-98, D-125.
 */
@Service
@RequiredArgsConstructor
public class CommentModerationServiceImpl implements CommentModerationService {

    private static final List<ReportStatus> OPEN = List.of(ReportStatus.OPEN, ReportStatus.REVIEWING);

    private final ModerationQueueRepository queue;
    private final PostReportRepository reports;
    private final ForumCommentRepository comments;
    private final ForumPostRepository posts;
    private final UserApi users;
    private final AdminApi admin;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CommentQueueItemResponse> queue(Integer page, Integer pageSize) {
        Page<QueueEntry> entries = queue.commentQueue(Paging.of(page, pageSize));
        Map<UUID, ModeratedCommentResponse> cards =
                cards(entries.map(QueueEntry::targetId).toList());
        return PageResponse.of(
                entries,
                entry -> new CommentQueueItemResponse(
                        cards.get(entry.targetId()),
                        entry.reportCount(),
                        entry.reasons(),
                        entry.firstReportedAt(),
                        entry.lastReportedAt()));
    }

    @Override
    @Transactional
    public CommentModerationCaseResponse open(UUID moderator, UUID commentId) {
        ForumComment comment = comment(commentId);
        List<PostReport> open = openReports(commentId);
        open.forEach(PostReport::review);
        reports.flush();
        Map<UUID, AuthorCard> reporters =
                authors(open.stream().map(PostReport::getReporterId).collect(Collectors.toSet()));
        List<CommentModerationCaseResponse.Report> listed = open.stream()
                .map(report -> new CommentModerationCaseResponse.Report(
                        report.getId(),
                        author(report.getReporterId(), reporters),
                        report.getReason(),
                        report.getDescription(),
                        report.getReportedContent(),
                        report.getStatus(),
                        report.getCreatedAt()))
                .toList();
        return new CommentModerationCaseResponse(cards(List.of(comment.getId())).get(comment.getId()), listed);
    }

    @Override
    @Transactional
    public CommentModerationDecisionResponse decide(UUID moderator, UUID commentId, ModerationDecisionRequest request) {
        ResolutionAction action = request.action();
        if (action.postOnly()) {
            throw new FieldValidationException(
                    action + " decides a post's reports, not a comment's", Map.of("action", "MSG01"));
        }
        ForumComment comment = comment(commentId);
        List<PostReport> open = openReports(commentId);
        Instant now = clock.instant();
        CommentStatus before = comment.getStatus();
        boolean banned = false;
        switch (action) {
            case HIDE_COMMENT -> hide(moderator, comment, before, request.note());
            case REMOVE_COMMENT -> {
                comment.remove();
                changed(moderator, AuditAction.COMMENT_REMOVED, comment, before, request.note());
            }
            case BAN_USER -> {
                hide(moderator, comment, before, request.note());
                banned = users.banForModeration(comment.getAuthorId(), now);
                if (banned) {
                    audit(
                            moderator,
                            AuditAction.ACCOUNT_BANNED,
                            AuditedEntity.USER_ACCOUNT,
                            comment.getAuthorId(),
                            null,
                            values("accountStatus", "BANNED", comment, request.note()));
                }
            }
            case WARN_USER ->
                audit(
                        moderator,
                        AuditAction.USER_WARNED,
                        AuditedEntity.USER_ACCOUNT,
                        comment.getAuthorId(),
                        null,
                        values("warning", "WARN_USER", comment, request.note()));
            case DISMISS ->
                audit(
                        moderator,
                        AuditAction.REPORTS_DISMISSED,
                        AuditedEntity.FORUM_COMMENT,
                        comment.getId(),
                        Map.of("openReports", open.size()),
                        values("openReports", 0, comment, request.note()));
            case HIDE_POST, REMOVE_POST -> throw new IllegalStateException("refused above: " + action);
        }
        open.forEach(report -> report.resolve(moderator, action, now));
        reports.flush();
        comments.flush();
        events.publishEvent(new CommentModerated(
                comment.getId(),
                comment.getPostId(),
                comment.getAuthorId(),
                action,
                open.stream().map(PostReport::getReporterId).distinct().toList(),
                moderator,
                request.note(),
                now));
        return new CommentModerationDecisionResponse(comment.getId(), action, comment.getStatus(), open.size(), banned);
    }

    @Override
    @Transactional
    public ModeratedCommentResponse restore(UUID moderator, UUID commentId) {
        ForumComment comment = comment(commentId);
        if (!comment.isHidden()) {
            throw new BusinessException(
                    ErrorCode.COMMENT_NOT_HIDDEN,
                    "comment " + commentId + " is " + comment.getStatus() + ", not hidden");
        }
        if (users.isBanned(comment.getAuthorId())) {
            throw new BusinessException(ErrorCode.AUTHOR_BANNED, "the author of comment " + commentId + " is banned");
        }
        comment.restore();
        audit(
                moderator,
                AuditAction.COMMENT_RESTORED,
                AuditedEntity.FORUM_COMMENT,
                comment.getId(),
                Map.of("commentStatus", CommentStatus.HIDDEN.name()),
                Map.of("commentStatus", CommentStatus.PUBLISHED.name()));
        comments.flush();
        return cards(List.of(comment.getId())).get(comment.getId());
    }

    private void hide(UUID moderator, ForumComment comment, CommentStatus before, String note) {
        comment.hide();
        changed(moderator, AuditAction.COMMENT_HIDDEN, comment, before, note);
    }

    // Audits a change of status; a decision that leaves the status as it was is audited by the decision alone.
    private void changed(UUID moderator, AuditAction action, ForumComment comment, CommentStatus before, String note) {
        if (comment.getStatus() != before) {
            audit(
                    moderator,
                    action,
                    AuditedEntity.FORUM_COMMENT,
                    comment.getId(),
                    Map.of("commentStatus", before.name()),
                    values("commentStatus", comment.getStatus().name(), comment, note));
        }
    }

    private void audit(
            UUID moderator,
            AuditAction action,
            AuditedEntity entity,
            UUID entityId,
            Map<String, Object> before,
            Map<String, Object> after) {
        admin.audit(new AuditEntry(moderator, action, entity, entityId, before, after));
    }

    // The new value with the comment it concerns and the moderator's note, so the entry explains itself.
    private static Map<String, Object> values(String field, Object value, ForumComment comment, String note) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(field, value);
        values.put("commentId", comment.getId().toString());
        values.put("postId", comment.getPostId().toString());
        values.put("note", note);
        return values;
    }

    /** The console's view of these comments, each with its post's title and its author, read once. */
    private Map<UUID, ModeratedCommentResponse> cards(List<UUID> commentIds) {
        List<ForumComment> found = commentIds.stream()
                .map(comments::findById)
                .flatMap(Optional::stream)
                .toList();
        Set<UUID> postIds = found.stream().map(ForumComment::getPostId).collect(Collectors.toSet());
        Map<UUID, String> titles = new HashMap<>();
        for (UUID postId : postIds) {
            posts.findById(postId).map(ForumPost::getTitle).ifPresent(title -> titles.put(postId, title));
        }
        Map<UUID, AuthorCard> authors = authors(
                new HashSet<>(found.stream().map(ForumComment::getAuthorId).toList()));
        return found.stream()
                .collect(Collectors.toMap(
                        ForumComment::getId,
                        comment -> new ModeratedCommentResponse(
                                comment.getId(),
                                comment.getPostId(),
                                titles.get(comment.getPostId()),
                                author(comment.getAuthorId(), authors),
                                comment.getContent(),
                                comment.getStatus(),
                                comment.getCreatedAt(),
                                comment.getUpdatedAt())));
    }

    private Map<UUID, AuthorCard> authors(Set<UUID> userIds) {
        return users.authors(userIds).stream().collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
    }

    private static AuthorResponse author(UUID userId, Map<UUID, AuthorCard> cards) {
        AuthorCard card = cards.get(userId);
        return new AuthorResponse(
                userId, card == null ? null : card.displayName(), card == null ? null : card.avatarUrl());
    }

    private ForumComment comment(UUID commentId) {
        return comments.findById(commentId).orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
    }

    private List<PostReport> openReports(UUID commentId) {
        List<PostReport> open = reports.findAllByCommentIdAndStatusInOrderByCreatedAtAscIdAsc(commentId, OPEN);
        if (open.isEmpty()) {
            throw new ResourceNotFoundException("Open reports of comment", commentId);
        }
        return open;
    }
}
