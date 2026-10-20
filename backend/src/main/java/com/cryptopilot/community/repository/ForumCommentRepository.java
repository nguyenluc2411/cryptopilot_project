package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.ForumComment;
import com.cryptopilot.community.model.enums.CommentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Transactional(readOnly = true)
    Optional<ForumComment> findById(UUID commentId);

    /** The author's comment with this key, or empty when there is none or another account wrote it. */
    @Transactional(readOnly = true)
    Optional<ForumComment> findByIdAndAuthorId(UUID commentId, UUID authorId);

    /**
     * One page of the comments that start a thread on a post, oldest first: the published ones, and a deleted one that
     * still has a published reply, so the reply keeps its place (SRS 3.7.3).
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select c from ForumComment c where c.postId = :postId and c.parentCommentId is null"
                    + " and (c.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED"
                    + " or (c.status = com.cryptopilot.community.model.enums.CommentStatus.DELETED"
                    + " and exists (select r from ForumComment r where r.parentCommentId = c.id"
                    + " and r.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED)))"
                    + " order by c.createdAt asc, c.id asc",
            countQuery = "select count(c) from ForumComment c where c.postId = :postId and c.parentCommentId is null"
                    + " and (c.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED"
                    + " or (c.status = com.cryptopilot.community.model.enums.CommentStatus.DELETED"
                    + " and exists (select r from ForumComment r where r.parentCommentId = c.id"
                    + " and r.status = com.cryptopilot.community.model.enums.CommentStatus.PUBLISHED)))")
    Page<ForumComment> findThreads(@Param("postId") UUID postId, Pageable page);

    /** The replies with this status in the threads of these comments, oldest first. */
    @Transactional(readOnly = true)
    List<ForumComment> findAllByParentCommentIdInAndStatusOrderByCreatedAtAscIdAsc(
            Collection<UUID> parentIds, CommentStatus status);

    /** Writes a comment. Not transactional here; the unit of work is the calling service's. */
    ForumComment save(ForumComment comment);

    /** Sends pending statements, so the audit instants are set before the comment is answered. */
    void flush();
}
