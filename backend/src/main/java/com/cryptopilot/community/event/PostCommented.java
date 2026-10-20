package com.cryptopilot.community.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A comment or a reply was published on a post. SRS 3.7.3 tells the post's author about new comments; the in-app
 * notification is created by the notification task that listens to this event, which is published inside the
 * transaction that stored the comment and is delivered after it commits (D-98). A listener decides itself not to
 * notify an author of their own comment.
 *
 * <p>Rule: UC-30; SRS 3.7.3; NSF-15; D-98.
 *
 * @param postId the post
 * @param postAuthorId who wrote the post, the one to tell
 * @param commentId the new comment
 * @param commenterId who wrote it
 * @param threadId the comment that starts its thread, or {@code null} when it starts one
 * @param commentedAt when it was published
 */
public record PostCommented(
        UUID postId, UUID postAuthorId, UUID commentId, UUID commenterId, UUID threadId, Instant commentedAt) {}
