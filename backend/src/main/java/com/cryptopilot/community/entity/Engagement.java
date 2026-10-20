package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.EngagementAction;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

/**
 * A like or a save of one Trader on exactly one post or comment (BR-45). Rows are written and removed by
 * {@link com.cryptopilot.community.repository.EngagementRepository} with single statements that the unique indexes
 * {@code uq_engagement_user_post} and {@code uq_engagement_user_comment} make idempotent, so this entity is only read.
 *
 * <p>Rule: BR-45; UC-31, UC-32.
 */
@Getter
@Entity
@Table(name = "engagement")
@AttributeOverride(name = "id", column = @Column(name = "engagement_id", nullable = false, updatable = false))
public class Engagement extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "post_id", updatable = false)
    private UUID postId;

    @Column(name = "comment_id", updatable = false)
    private UUID commentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32, updatable = false)
    private EngagementAction actionType;

    /** For JPA only. */
    protected Engagement() {}
}
