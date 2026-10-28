package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.Engagement;
import com.cryptopilot.community.model.enums.EngagementAction;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Likes and saves (BR-45). Each is one statement: an insert that a second like of the same Trader leaves alone
 * ({@code on conflict ... do nothing} on the partial unique index of the target), and a delete. So two clicks at
 * once, or a client that repeats a request, never make two rows and never fail; the database decides.
 *
 * <p>Rule: BR-45; UC-31, UC-32.
 *
 * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, INSERT, "ON CONFLICT Clause"
 * (a partial unique index is named by its columns and predicate).
 */
public interface EngagementRepository extends Repository<Engagement, UUID> {

    /** Records the action on a post unless the Trader already took it; 1 when a row was added. */
    @Modifying
    @Query(nativeQuery = true, value = """
                    insert into engagement (engagement_id, user_id, post_id, action_type, created_at, updated_at)
                    values (:id, :userId, :postId, :action, :now, :now)
                    on conflict (user_id, post_id, action_type) where post_id is not null do nothing""")
    int addToPost(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("postId") UUID postId,
            @Param("action") String action,
            @Param("now") Instant now);

    /** Records the action on a comment unless the Trader already took it; 1 when a row was added. */
    @Modifying
    @Query(nativeQuery = true, value = """
                    insert into engagement (engagement_id, user_id, comment_id, action_type, created_at, updated_at)
                    values (:id, :userId, :commentId, :action, :now, :now)
                    on conflict (user_id, comment_id, action_type) where comment_id is not null do nothing""")
    int addToComment(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("commentId") UUID commentId,
            @Param("action") String action,
            @Param("now") Instant now);

    /**
     * Records the member's Bullish or Bearish vote on a post, replacing the other one (D-125): one statement on the
     * partial unique index of the votes, so two votes at once leave one row.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
                    insert into engagement (engagement_id, user_id, post_id, action_type, created_at, updated_at)
                    values (:id, :userId, :postId, :vote, :now, :now)
                    on conflict (user_id, post_id) where action_type in ('BULLISH', 'BEARISH')
                    do update set action_type = excluded.action_type, updated_at = excluded.updated_at""")
    int votePost(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("postId") UUID postId,
            @Param("vote") String vote,
            @Param("now") Instant now);

    /** Takes the member's vote on a post back; 0 when there was none (D-125). */
    @Modifying
    @Query(
            value = "delete from engagement where user_id = :userId and post_id = :postId"
                    + " and action_type in ('BULLISH', 'BEARISH')",
            nativeQuery = true)
    int unvotePost(@Param("userId") UUID userId, @Param("postId") UUID postId);

    /** Takes the action back from a post; 0 when there was none. */
    @Modifying
    @Query(
            value = "delete from engagement where user_id = :userId and post_id = :postId and action_type = :action",
            nativeQuery = true)
    int removeFromPost(@Param("userId") UUID userId, @Param("postId") UUID postId, @Param("action") String action);

    /** Takes the action back from a comment; 0 when there was none. */
    @Modifying
    @Query(
            value = "delete from engagement where user_id = :userId and comment_id = :commentId"
                    + " and action_type = :action",
            nativeQuery = true)
    int removeFromComment(
            @Param("userId") UUID userId, @Param("commentId") UUID commentId, @Param("action") String action);

    @Transactional(readOnly = true)
    long countByPostIdAndActionType(UUID postId, EngagementAction action);

    @Transactional(readOnly = true)
    long countByCommentIdAndActionType(UUID commentId, EngagementAction action);

    /** The likes of each of these comments that has any, as (comment id, count) pairs. */
    @Transactional(readOnly = true)
    @Query(nativeQuery = true, value = """
                    select comment_id, count(*) from engagement
                     where comment_id in (:commentIds) and action_type = 'LIKE'
                     group by comment_id""")
    List<Object[]> countLikesByComment(@Param("commentIds") Collection<UUID> commentIds);

    /** Which of these comments the Trader liked. */
    @Transactional(readOnly = true)
    @Query(nativeQuery = true, value = """
                    select comment_id from engagement
                     where comment_id in (:commentIds) and user_id = :userId and action_type = 'LIKE'""")
    List<UUID> commentsLikedBy(@Param("commentIds") Collection<UUID> commentIds, @Param("userId") UUID userId);
}
