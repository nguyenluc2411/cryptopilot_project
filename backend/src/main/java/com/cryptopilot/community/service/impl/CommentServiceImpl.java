package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.CommentRequest;
import com.cryptopilot.community.dto.response.AuthorResponse;
import com.cryptopilot.community.dto.response.CommentResponse;
import com.cryptopilot.community.entity.ForumComment;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.event.PostCommented;
import com.cryptopilot.community.repository.EngagementRepository;
import com.cryptopilot.community.repository.ForumCommentRepository;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.service.CommentService;
import com.cryptopilot.user.AuthorCard;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * The comment use cases of SCR-25.
 *
 * <p>Every comment is reached through its post, and the post through {@link CommunityAccess}: the comments of a post the
 * caller may not see, by BR-47 or because a block stands between the caller and its author, do not exist for the
 * caller (BR-47, D-125), and neither do the comments of a member in a block with the caller. A reply names a published comment of the same
 * post and joins its thread, one level deep (D-98). A deleted comment is listed only while a published reply still
 * hangs on it, without its text or author (SRS 3.7.3). A new comment is announced with {@link PostCommented}.
 *
 * <p>Rule: UC-30, UC-31; BR-45, BR-47; SRS 3.7.3; D-98.
 */
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** Stands for a Guest in the queries that leave out the comments of blocked members: no account has this key. */
    private static final UUID NOBODY = new UUID(0L, 0L);

    private final ForumCommentRepository comments;
    private final ForumPostRepository posts;
    private final EngagementRepository engagements;
    private final CommunityAccess access;
    private final MentionResolver mentions;
    private final UserApi users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(UUID caller, UUID postId, Integer page, Integer pageSize) {
        visiblePost(caller, postId);
        PageRequest request = Paging.of(page, pageSize);
        UUID viewer = caller == null ? NOBODY : caller;
        Page<ForumComment> threads = comments.findThreads(postId, viewer, request);
        List<ForumComment> replies = threads.isEmpty()
                ? List.of()
                : comments.findReplies(threads.map(ForumComment::getId).toList(), viewer);
        List<ForumComment> all = new ArrayList<>(threads.getContent());
        all.addAll(replies);
        Context context = context(all, caller);
        Map<UUID, List<CommentResponse>> repliesByThread = new HashMap<>();
        for (ForumComment reply : replies) {
            repliesByThread
                    .computeIfAbsent(reply.getParentCommentId(), key -> new ArrayList<>())
                    .add(response(reply, context, List.of()));
        }
        return PageResponse.of(
                threads, thread -> response(thread, context, repliesByThread.getOrDefault(thread.getId(), List.of())));
    }

    @Override
    @Transactional
    public CommentResponse create(UUID caller, UUID postId, CommentRequest request) {
        ForumPost post = visiblePost(caller, postId);
        ForumComment comment;
        UUID repliedTo = null;
        if (request.parentCommentId() == null) {
            comment = ForumComment.on(post.getId(), caller, request.content());
        } else {
            ForumComment parent = comments.findById(request.parentCommentId())
                    .filter(found -> found.getPostId().equals(post.getId()) && found.isPublished())
                    // A member in a block with the parent's author cannot see the parent, so cannot reply (D-125).
                    .filter(found -> access.canSee(found, caller))
                    .orElseThrow(() -> new ResourceNotFoundException("Comment", request.parentCommentId()));
            comment = ForumComment.replyTo(parent, caller, request.content());
            repliedTo = parent.getAuthorId();
        }
        comments.save(comment);
        comments.flush();
        events.publishEvent(new PostCommented(
                post.getId(),
                post.getAuthorId(),
                comment.getId(),
                caller,
                comment.getParentCommentId(),
                repliedTo,
                clock.instant()));
        mentions.mention(post.getId(), comment.getId(), caller, request.mentions());
        return response(comment, context(List.of(comment), caller), List.of());
    }

    @Override
    @Transactional
    public CommentResponse update(UUID caller, UUID commentId, CommentRequest request) {
        ForumComment comment = owned(caller, commentId);
        comment.edit(request.content());
        comments.flush();
        mentions.mention(comment.getPostId(), comment.getId(), caller, request.mentions());
        return response(comment, context(List.of(comment), caller), List.of());
    }

    @Override
    @Transactional
    public void delete(UUID caller, UUID commentId) {
        owned(caller, commentId).delete();
    }

    private ForumComment owned(UUID caller, UUID commentId) {
        return comments.findByIdAndAuthorId(commentId, caller)
                // A hidden comment is kept as moderation saw it (D-99).
                .filter(ForumComment::isPublished)
                .filter(comment -> posts.findById(comment.getPostId())
                        .filter(post -> access.canSee(post, caller))
                        .isPresent())
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
    }

    private ForumPost visiblePost(UUID caller, UUID postId) {
        return posts.findById(postId)
                .filter(post -> access.canSee(post, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }

    /** The authors, like counts and the caller's likes of these comments, each read once. */
    private Context context(Collection<ForumComment> listed, UUID caller) {
        Set<UUID> ids = listed.stream().map(ForumComment::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new Context(Map.of(), Map.of(), Set.of(), caller);
        }
        Map<UUID, AuthorCard> authors =
                users.authors(listed.stream().map(ForumComment::getAuthorId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(AuthorCard::userId, Function.identity()));
        Map<UUID, Long> likes = new HashMap<>();
        for (Object[] row : engagements.countLikesByComment(ids)) {
            likes.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return new Context(
                authors,
                likes,
                caller == null ? Set.of() : new HashSet<>(engagements.commentsLikedBy(ids, caller)),
                caller);
    }

    private static CommentResponse response(ForumComment comment, Context context, List<CommentResponse> replies) {
        boolean deleted = comment.isDeleted();
        boolean hidden = comment.isHidden();
        boolean gone = deleted || hidden;
        AuthorCard card = context.authors().get(comment.getAuthorId());
        AuthorResponse author = gone
                ? null
                : new AuthorResponse(
                        comment.getAuthorId(),
                        card == null ? null : card.displayName(),
                        card == null ? null : card.avatarUrl());
        return new CommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getParentCommentId(),
                author,
                gone ? null : comment.getContent(),
                deleted,
                hidden,
                context.likes().getOrDefault(comment.getId(), 0L),
                context.liked().contains(comment.getId()),
                !gone && comment.getAuthorId().equals(context.caller()),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                replies);
    }

    private record Context(Map<UUID, AuthorCard> authors, Map<UUID, Long> likes, Set<UUID> liked, UUID caller) {}
}
