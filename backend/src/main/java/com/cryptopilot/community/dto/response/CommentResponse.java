package com.cryptopilot.community.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A comment of SCR-25 with its replies. A deleted comment is listed only while it has replies, without its text or
 * author, so the client shows "Comment deleted" in its place (SRS 3.7.3).
 *
 * <p>Rule: UC-30, UC-31; BR-45, BR-47; SRS 3.7.3.
 *
 * @param id the comment
 * @param postId the post
 * @param parentCommentId the comment that starts its thread, or {@code null} for a thread's first comment
 * @param author who wrote it; {@code null} when deleted
 * @param content the text; {@code null} when deleted
 * @param deleted whether its author deleted it
 * @param likeCount its likes
 * @param likedByMe whether the caller liked it
 * @param mine whether the caller wrote it, so the client offers Edit and Delete
 * @param createdAt when it was published
 * @param updatedAt when it was last changed
 * @param replies the published replies of a thread's first comment, oldest first; empty for a reply
 */
public record CommentResponse(
        UUID id,
        UUID postId,
        UUID parentCommentId,
        AuthorResponse author,
        String content,
        boolean deleted,
        long likeCount,
        boolean likedByMe,
        boolean mine,
        Instant createdAt,
        Instant updatedAt,
        List<CommentResponse> replies) {}
