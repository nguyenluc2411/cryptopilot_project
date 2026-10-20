package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.UserFollow;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who follows whom (D-99). Following and unfollowing are one statement each, so a repeated or concurrent click keeps
 * one row or none and never fails.
 *
 * <p>Rule: D-99.
 */
public interface UserFollowRepository extends Repository<UserFollow, UUID> {

    /** Follows a member unless already following; 1 when a row was added. */
    @Modifying
    @Query(value = """
                    insert into user_follow (follow_id, follower_id, followee_id, created_at, updated_at)
                    values (:id, :follower, :followee, :now, :now)
                    on conflict (follower_id, followee_id) do nothing""", nativeQuery = true)
    int follow(
            @Param("id") UUID id,
            @Param("follower") UUID follower,
            @Param("followee") UUID followee,
            @Param("now") Instant now);

    /** Stops following a member; 0 when not following. */
    @Modifying
    @Query(
            value = "delete from user_follow where follower_id = :follower and followee_id = :followee",
            nativeQuery = true)
    int unfollow(@Param("follower") UUID follower, @Param("followee") UUID followee);

    @Transactional(readOnly = true)
    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    /** How many follow the member. */
    @Transactional(readOnly = true)
    long countByFolloweeId(UUID followeeId);

    /** How many the member follows. */
    @Transactional(readOnly = true)
    long countByFollowerId(UUID followerId);

    /** One page of the member's followers, the most recent first. */
    @Transactional(readOnly = true)
    Page<UserFollow> findByFolloweeIdOrderByCreatedAtDescIdDesc(UUID followeeId, Pageable page);

    /** One page of the members the member follows, the most recent first. */
    @Transactional(readOnly = true)
    Page<UserFollow> findByFollowerIdOrderByCreatedAtDescIdDesc(UUID followerId, Pageable page);
}
