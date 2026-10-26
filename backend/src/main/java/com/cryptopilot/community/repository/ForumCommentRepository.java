package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.ForumComment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the comments of posts.
 *
 * <p>Rule: UC-30; BR-47; SRS 3.7.3.
 */
public interface ForumCommentRepository extends Repository<ForumComment, UUID> {

    /** A thread's first comment is shown while published, or deleted or hidden with a published reply. */
    String THREAD_SHOWN = " and (c.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED"
            + " or (c.status in (com.cryptopilot.community.model.enums.CommentStatus.DELETED,"
            + " com.cryptopilot.community.model.enums.CommentStatus.HIDDEN)"
            + " and exists (select r from ForumComment r where r.parentCommentId = c.id"
            + " and r.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED)))";

    /** A block works both ways: a comment of a member in a block with the viewer is not shown to the viewer (D-125). */
    String NOT_BLOCKED = " and not exists (select b from UserRelation b"
            + " where b.type = com.cryptopilot.community.model.enums.RelationType.BLOCK"
            + " and ((b.sourceUserId = :viewer and b.targetUserId = c.authorId)"
            + " or (b.sourceUserId = c.authorId and b.targetUserId = :viewer)))";

    @Transactional(readOnly = true)
    Optional<ForumComment> findById(UUID commentId);

    /** The author's comment with this key, or empty when there is none or another account wrote it. */
    @Transactional(readOnly = true)
    Optional<ForumComment> findByIdAndAuthorId(UUID commentId, UUID authorId);

    /**
     * One page of the comments that start a thread on a post, oldest first: the published ones, and a deleted or
     * hidden one that still has a published reply, so the reply keeps its place (SRS 3.7.3). A thread started by a
     * member in a block with the viewer is left out (D-125).
     *
     * @param viewer the reader, or a key no account has for a Guest
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select c from ForumComment c where c.postId = :postId and c.parentCommentId is null" + THREAD_SHOWN
                    + NOT_BLOCKED + " order by c.createdAt asc, c.id asc",
            countQuery = "select count(c) from ForumComment c where c.postId = :postId and c.parentCommentId is null"
                    + THREAD_SHOWN + NOT_BLOCKED)
    Page<ForumComment> findThreads(@Param("postId") UUID postId, @Param("viewer") UUID viewer, Pageable page);

    /** The published replies in the threads of these comments, oldest first, without those of members in a block with the viewer. */
    @Transactional(readOnly = true)
    @Query("select c from ForumComment c where c.parentCommentId in :threads"
            + " and c.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED" + NOT_BLOCKED
            + " order by c.createdAt asc, c.id asc")
    List<ForumComment> findReplies(@Param("threads") Collection<UUID> threads, @Param("viewer") UUID viewer);

    /** Hides every published comment of a member, when the member is banned (D-99); how many it hid. */
    @Modifying
    @Query(
            value = "update forum_comment set comment_status = 'HIDDEN', updated_at = :now, version = version + 1"
                    + " where author_id = :author and comment_status = 'PUBLISHED'",
            nativeQuery = true)
    int hideAllOf(@Param("author") UUID author, @Param("now") Instant now);

    /** Writes a comment. Not transactional here; the unit of work is the calling service's. */
    ForumComment save(ForumComment comment);

    /** Sends pending statements, so the audit instants are set before the comment is answered. */
    void flush();
}
