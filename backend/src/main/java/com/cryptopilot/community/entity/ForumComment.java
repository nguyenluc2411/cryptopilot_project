package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.CommentStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A comment on a post, or a reply to one (UC-30). Replies are one level deep: a reply to a reply hangs on the comment
 * that started the thread, so a thread is a comment and its replies in the order they were written (D-98).
 *
 * <p>Deleting a comment sets {@link CommentStatus#DELETED} (BR-47); the row stays, so its replies keep their place. A
 * moderator hides a reported comment ({@link CommentStatus#HIDDEN}, restorable) or removes it (D-99).
 *
 * <p>Rule: UC-30; BR-47; SRS 3.7.3.
 */
@Getter
@Entity
@Table(name = "forum_comment")
@AttributeOverride(name = "id", column = @Column(name = "comment_id", nullable = false, updatable = false))
public class ForumComment extends BaseEntity {

    /** Longest comment, in characters (SRS 3.7.3). */
    public static final int CONTENT_MAX_LENGTH = 2_000;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    /** The comment that starts the thread, or {@code null} for a comment that starts one. */
    @Column(name = "parent_comment_id", updatable = false)
    private UUID parentCommentId;

    @Column(name = "content", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_status", nullable = false, length = 32)
    private CommentStatus status;

    /** For JPA only. */
    protected ForumComment() {}

    private ForumComment(UUID postId, UUID authorId, UUID parentCommentId, String content) {
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
        this.parentCommentId = parentCommentId;
        this.content = requireContent(content);
        this.status = CommentStatus.PUBLISHED;
    }

    /** A comment that starts a thread on a post. */
    public static ForumComment on(UUID postId, UUID authorId, String content) {
        return new ForumComment(postId, authorId, null, content);
    }

    /**
     * A reply in the thread of {@code parent}: under {@code parent} itself, or under the comment that started its
     * thread when {@code parent} is a reply.
     */
    public static ForumComment replyTo(ForumComment parent, UUID authorId, String content) {
        UUID thread = parent.parentCommentId == null ? parent.getId() : parent.parentCommentId;
        return new ForumComment(parent.postId, authorId, thread, content);
    }

    /** Replaces the text of a published comment; a deleted or hidden one keeps its text. */
    public void edit(String content) {
        if (!isPublished()) {
            throw new IllegalStateException("comment " + getId() + " is " + status);
        }
        this.content = requireContent(content);
    }

    /** Deletes the comment (BR-47); deleting it again changes nothing. */
    public void delete() {
        this.status = CommentStatus.DELETED;
    }

    /** Hides a published comment for a moderation decision or a ban (D-99); any other comment is left as it is. */
    public void hide() {
        if (status == CommentStatus.PUBLISHED) {
            this.status = CommentStatus.HIDDEN;
        }
    }

    /** Removes the comment for a moderation decision (D-99): deleted for everyone, its text kept for Admin. */
    public void remove() {
        this.status = CommentStatus.DELETED;
    }

    /** Shows a hidden comment again (D-99). */
    public void restore() {
        if (status != CommentStatus.HIDDEN) {
            throw new IllegalStateException("comment " + getId() + " is " + status + ", not hidden");
        }
        this.status = CommentStatus.PUBLISHED;
    }

    public boolean isDeleted() {
        return status == CommentStatus.DELETED;
    }

    public boolean isHidden() {
        return status == CommentStatus.HIDDEN;
    }

    /** Whether the comment is shown with its text: published, neither deleted nor hidden. */
    public boolean isPublished() {
        return status == CommentStatus.PUBLISHED;
    }

    private static String requireContent(String content) {
        String text =
                Objects.requireNonNull(content, "content must not be null").strip();
        if (text.isEmpty() || text.length() > CONTENT_MAX_LENGTH) {
            throw new IllegalArgumentException("a comment has 1 to " + CONTENT_MAX_LENGTH + " characters");
        }
        return text;
    }
}
