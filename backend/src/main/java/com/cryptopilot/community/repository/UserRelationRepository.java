package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.UserRelation;
import com.cryptopilot.community.model.enums.RelationType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who follows or blocks whom (D-125). Each change is one statement, so a repeated or concurrent click keeps one row or
 * none and never fails.
 *
 * <p>Rule: D-125.
 */
public interface UserRelationRepository extends Repository<UserRelation, UUID> {

    /** Follows a member unless already following or blocking them; 1 when a row was added. */
    @Modifying
    @Query(value = """
                    insert into user_relation
                           (relation_id, source_user_id, target_user_id, relation_type, created_at, updated_at)
                    values (:id, :source, :target, 'FOLLOW', :now, :now)
                    on conflict (source_user_id, target_user_id) do nothing""", nativeQuery = true)
    int follow(
            @Param("id") UUID id,
            @Param("source") UUID source,
            @Param("target") UUID target,
            @Param("now") Instant now);

    /** Blocks a member, replacing a follow of them; 1 when the block is new. */
    @Modifying
    @Query(value = """
                    insert into user_relation
                           (relation_id, source_user_id, target_user_id, relation_type, created_at, updated_at)
                    values (:id, :source, :target, 'BLOCK', :now, :now)
                    on conflict (source_user_id, target_user_id) do update
                       set relation_type = 'BLOCK', created_at = excluded.created_at, updated_at = excluded.updated_at,
                           version = user_relation.version + 1
                     where user_relation.relation_type <> 'BLOCK'""", nativeQuery = true)
    int block(
            @Param("id") UUID id,
            @Param("source") UUID source,
            @Param("target") UUID target,
            @Param("now") Instant now);

    /** Removes a relation of this type from one member to another; 0 when there is none. */
    @Modifying
    @Query(
            value = "delete from user_relation where source_user_id = :source and target_user_id = :target"
                    + " and relation_type = :type",
            nativeQuery = true)
    int remove(@Param("source") UUID source, @Param("target") UUID target, @Param("type") String type);

    /** Removes every relation from or to a member, when the member is banned. */
    @Modifying
    @Query(
            value = "delete from user_relation where source_user_id = :member or target_user_id = :member",
            nativeQuery = true)
    int removeAllOf(@Param("member") UUID member);

    @Transactional(readOnly = true)
    boolean existsBySourceUserIdAndTargetUserIdAndType(UUID sourceUserId, UUID targetUserId, RelationType type);

    /** Whether either of two members blocks the other. */
    @Transactional(readOnly = true)
    @Query("""
            select count(r) > 0 from UserRelation r
             where r.type = com.cryptopilot.community.model.enums.RelationType.BLOCK
               and ((r.sourceUserId = :one and r.targetUserId = :other)
                    or (r.sourceUserId = :other and r.targetUserId = :one))""")
    boolean blockedBetween(@Param("one") UUID one, @Param("other") UUID other);

    /** Which of these members block {@code member} or are blocked by them. */
    @Transactional(readOnly = true)
    @Query("""
            select case when r.sourceUserId = :member then r.targetUserId else r.sourceUserId end
              from UserRelation r
             where r.type = com.cryptopilot.community.model.enums.RelationType.BLOCK
               and ((r.sourceUserId = :member and r.targetUserId in :others)
                    or (r.targetUserId = :member and r.sourceUserId in :others))""")
    List<UUID> blockedEitherWay(@Param("member") UUID member, @Param("others") Collection<UUID> others);

    /** Which of these members block {@code target}. */
    @Transactional(readOnly = true)
    @Query("""
            select r.sourceUserId from UserRelation r
             where r.type = com.cryptopilot.community.model.enums.RelationType.BLOCK
               and r.targetUserId = :target and r.sourceUserId in :sources""")
    List<UUID> blockersAmong(@Param("sources") Collection<UUID> sources, @Param("target") UUID target);

    /** How many members have this relation to the target, e.g. its followers. */
    @Transactional(readOnly = true)
    long countByTargetUserIdAndType(UUID targetUserId, RelationType type);

    /** How many members the source has this relation to, e.g. the members it follows. */
    @Transactional(readOnly = true)
    long countBySourceUserIdAndType(UUID sourceUserId, RelationType type);

    /** One page of a member's followers the viewer may see (no block either way, D-125), the most recent first. */
    @Transactional(readOnly = true)
    @Query("select r from UserRelation r where r.targetUserId = :member"
            + " and r.type = com.cryptopilot.community.model.enums.RelationType.FOLLOW"
            + " and not exists (select b from UserRelation b"
            + " where b.type = com.cryptopilot.community.model.enums.RelationType.BLOCK"
            + " and ((b.sourceUserId = :viewer and b.targetUserId = r.sourceUserId)"
            + " or (b.sourceUserId = r.sourceUserId and b.targetUserId = :viewer)))"
            + " order by r.createdAt desc, r.id desc")
    Page<UserRelation> findFollowersSeenBy(@Param("member") UUID member, @Param("viewer") UUID viewer, Pageable page);

    /** One page of the members a member follows that the viewer may see (D-125), the most recent first. */
    @Transactional(readOnly = true)
    @Query("select r from UserRelation r where r.sourceUserId = :member"
            + " and r.type = com.cryptopilot.community.model.enums.RelationType.FOLLOW"
            + " and not exists (select b from UserRelation b"
            + " where b.type = com.cryptopilot.community.model.enums.RelationType.BLOCK"
            + " and ((b.sourceUserId = :viewer and b.targetUserId = r.targetUserId)"
            + " or (b.sourceUserId = r.targetUserId and b.targetUserId = :viewer)))"
            + " order by r.createdAt desc, r.id desc")
    Page<UserRelation> findFollowingSeenBy(@Param("member") UUID member, @Param("viewer") UUID viewer, Pageable page);

    /** One page of the members with this relation to the target, the most recent first. */
    @Transactional(readOnly = true)
    Page<UserRelation> findByTargetUserIdAndTypeOrderByCreatedAtDescIdDesc(
            UUID targetUserId, RelationType type, Pageable page);

    /** One page of the members the source has this relation to, the most recent first. */
    @Transactional(readOnly = true)
    Page<UserRelation> findBySourceUserIdAndTypeOrderByCreatedAtDescIdDesc(
            UUID sourceUserId, RelationType type, Pageable page);
}
