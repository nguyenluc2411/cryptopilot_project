package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.CommentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A reported comment as the moderation console shows it (D-99): its current text and status whatever they are, and
 * the post it belongs to.
 *
 * <p>Rule: UC-43; BR-47 (Admin sees hidden and deleted content); D-99.
 *
 * @param id the comment
 * @param postId its post
 * @param postTitle the post's title
 * @param author who wrote it
 * @param content its current text
 * @param status PUBLISHED, HIDDEN or DELETED
 * @param createdAt when it was published
 * @param updatedAt when it was last changed
 */
public record ModeratedCommentResponse(
        UUID id,
        UUID postId,
        String postTitle,
        AuthorResponse author,
        String content,
        CommentStatus status,
        Instant createdAt,
        Instant updatedAt) {}
