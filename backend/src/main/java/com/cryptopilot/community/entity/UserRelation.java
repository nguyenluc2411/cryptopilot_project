package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.RelationType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

/**
 * One member following or blocking another (D-99, CoinMarketCap Community): one row per direction
 * ({@code uq_user_relation_source_target}), never about oneself ({@code ck_user_relation_not_self}). Written by
 * {@link com.cryptopilot.community.repository.UserRelationRepository} with single idempotent statements, so this
 * entity is read only.
 *
 * <p>Rule: D-99.
 */
@Getter
@Entity
@Table(name = "user_relation")
@AttributeOverride(name = "id", column = @Column(name = "relation_id", nullable = false, updatable = false))
public class UserRelation extends BaseEntity {

    /** Who follows or blocks. */
    @Column(name = "source_user_id", nullable = false, updatable = false)
    private UUID sourceUserId;

    /** Who is followed or blocked. */
    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 32)
    private RelationType type;

    /** For JPA only. */
    protected UserRelation() {}
}
