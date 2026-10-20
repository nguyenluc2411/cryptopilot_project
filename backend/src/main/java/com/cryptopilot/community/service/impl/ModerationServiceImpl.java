package com.cryptopilot.community.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.ModerationDecisionRequest;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.ModerationCaseResponse;
import com.cryptopilot.community.dto.response.ModerationDecisionResponse;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.dto.response.QueueItemResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.entity.PostReport;
import com.cryptopilot.community.event.PostModerated;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.QueueEntry;
import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.community.model.enums.ReportStatus;
import com.cryptopilot.community.model.enums.ResolutionAction;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.ModerationQueueRepository;
import com.cryptopilot.community.repository.PostReportRepository;
import com.cryptopilot.community.service.ModerationService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The moderation queue and its decisions (UC-43).
 *
 * <p>A decision resolves every OPEN and REVIEWING report of the post together, with the moderator, the action and the
 * time. HIDE_POST hides the post; REMOVE_POST deletes it for everyone but Admin (D-99, the registered topic's "hide
 * or remove"), keeping its files as the evidence of the decision; BAN_USER hides it and bans its author, which ends every session of the author
 * (BR-06) in the same transaction; WARN_USER and DISMISS leave the post as it is. The decision, the hidden post and the
 * ban are each written to the audit log in that transaction (BR-57), and {@link PostModerated} tells the author and
 * the reporters after it commits. A hidden post can be restored.
 *
 * <p>Rule: UC-43; BR-06, BR-47, BR-57, BR-58; SRS 3.11.3; D-98.
 */
@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements ModerationService {

    private static final List<ReportStatus> OPEN = List.of(ReportStatus.OPEN, ReportStatus.REVIEWING);

    private final ModerationQueueRepository queue;
    private final PostReportRepository reports;
    private final ForumPostRepository posts;
    private final PostAssembler assembler;
    private final UserApi users;
    private final AdminApi admin;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QueueItemResponse> queue(UUID moderator, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        Page<QueueEntry> entries = queue.queue(request);
        List<UUID> ids = entries.map(QueueEntry::postId).toList();
        Map<UUID, PostSummaryResponse> cards =
                assembler.page(new FeedPage(ids, ids.size()), request, moderator).items().stream()
                        .collect(Collectors.toMap(PostSummaryResponse::id, Function.identity()));
        return PageResponse.of(
                entries,
                entry -> new QueueItemResponse(
                        cards.get(entry.postId()),
                        entry.reportCount(),
                        entry.reasons(),
                        entry.firstReportedAt(),
                        entry.lastReportedAt()));
    }

    @Override
    @Transactional
    public ModerationCaseResponse open(UUID moderator, UUID postId) {
        ForumPost post = post(postId);
        List<PostReport> open = openReports(postId);
        open.forEach(PostReport::review);
        reports.flush();
        Map<UUID, AuthorCard> reporters =
                users.authors(open.stream().map(PostReport::getReporterId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
        List<ModerationCaseResponse.Report> listed = open.stream()
                .map(report -> {
                    AuthorCard card = reporters.get(report.getReporterId());
                    return new ModerationCaseResponse.Report(
                            report.getId(),
                            new AuthorResponse(
                                    report.getReporterId(),
                                    card == null ? null : card.displayName(),
                                    card == null ? null : card.avatarUrl()),
                            report.getReason(),
                            report.getDescription(),
                            report.getStatus(),
                            report.getCreatedAt());
                })
                .toList();
        return new ModerationCaseResponse(assembler.detail(post, moderator), listed);
    }

    @Override
    @Transactional
    public ModerationDecisionResponse decide(UUID moderator, UUID postId, ModerationDecisionRequest request) {
        ForumPost post = post(postId);
        List<PostReport> open = openReports(postId);
        Instant now = clock.instant();
        ResolutionAction action = request.action();
        PostStatus before = post.getStatus();
        boolean banned = false;
        switch (action) {
            case HIDE_POST -> hide(moderator, post, before, request.note());
            case REMOVE_POST -> remove(moderator, post, before, request.note());
            case BAN_USER -> {
                hide(moderator, post, before, request.note());
                banned = users.banForModeration(post.getAuthorId(), now);
                if (banned) {
                    audit(
                            moderator,
                            AuditAction.ACCOUNT_BANNED,
                            AuditedEntity.USER_ACCOUNT,
                            post.getAuthorId(),
                            null,
                            values("accountStatus", "BANNED", post, request.note()));
                }
            }
            case WARN_USER ->
                audit(
                        moderator,
                        AuditAction.USER_WARNED,
                        AuditedEntity.USER_ACCOUNT,
                        post.getAuthorId(),
                        null,
                        values("warning", "WARN_USER", post, request.note()));
            case DISMISS ->
                audit(
                        moderator,
                        AuditAction.REPORTS_DISMISSED,
                        AuditedEntity.FORUM_POST,
                        post.getId(),
                        Map.of("openReports", open.size()),
                        values("openReports", 0, post, request.note()));
        }
        open.forEach(report -> report.resolve(moderator, action, now));
        reports.flush();
        posts.flush();
        events.publishEvent(new PostModerated(
                post.getId(),
                post.getAuthorId(),
                action,
                open.stream().map(PostReport::getReporterId).distinct().toList(),
                moderator,
                request.note(),
                now));
        return new ModerationDecisionResponse(post.getId(), action, post.getStatus(), open.size(), banned);
    }

    @Override
    @Transactional
    public PostDetailResponse restore(UUID moderator, UUID postId) {
        ForumPost post = post(postId);
        if (post.getStatus() != PostStatus.HIDDEN) {
            throw new BusinessException(
                    ErrorCode.POST_NOT_HIDDEN, "post " + postId + " is " + post.getStatus() + ", not hidden");
        }
        post.restore();
        audit(
                moderator,
                AuditAction.POST_RESTORED,
                AuditedEntity.FORUM_POST,
                post.getId(),
                Map.of("postStatus", PostStatus.HIDDEN.name()),
                Map.of("postStatus", PostStatus.PUBLISHED.name()));
        posts.flush();
        return assembler.detail(post, moderator);
    }

    @Override
    @Transactional(readOnly = true)
    public PostDetailResponse post(UUID moderator, UUID postId) {
        return assembler.detail(post(postId), moderator);
    }

    private void remove(UUID moderator, ForumPost post, PostStatus before, String note) {
        post.remove();
        if (post.getStatus() != before) {
            audit(
                    moderator,
                    AuditAction.POST_REMOVED,
                    AuditedEntity.FORUM_POST,
                    post.getId(),
                    Map.of("postStatus", before.name()),
                    values("postStatus", post.getStatus().name(), post, note));
        }
    }

    private void hide(UUID moderator, ForumPost post, PostStatus before, String note) {
        post.hide();
        if (post.getStatus() != before) {
            audit(
                    moderator,
                    AuditAction.POST_HIDDEN,
                    AuditedEntity.FORUM_POST,
                    post.getId(),
                    Map.of("postStatus", before.name()),
                    values("postStatus", post.getStatus().name(), post, note));
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

    // The new value with the post it concerns and the moderator's note, so the entry explains itself.
    private static Map<String, Object> values(String field, Object value, ForumPost post, String note) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(field, value);
        values.put("postId", post.getId().toString());
        values.put("note", note);
        return values;
    }

    private ForumPost post(UUID postId) {
        return posts.findById(postId).orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }

    private List<PostReport> openReports(UUID postId) {
        List<PostReport> open = reports.findAllByPostIdAndStatusInOrderByCreatedAtAscIdAsc(postId, OPEN);
        if (open.isEmpty()) {
            throw new ResourceNotFoundException("Open reports of post", postId);
        }
        return open;
    }
}
