package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

/**
 * One member following another (D-99, CoinMarketCap Community): once ({@code uq_user_follow_follower_followee}),
 * never oneself ({@code ck_user_follow_not_self}). Written by
 * {@link com.cryptopilot.community.repository.UserFollowRepository} with single idempotent statements, so this entity
 * is read only.
 *
 * <p>Rule: D-99.
 */
@Getter
@Entity
@Table(name = "user_follow")
@AttributeOverride(name = "id", column = @Column(name = "follow_id", nullable = false, updatable = false))
public class UserFollow extends BaseEntity {

    @Column(name = "follower_id", nullable = false, updatable = false)
    private UUID followerId;

    @Column(name = "followee_id", nullable = false, updatable = false)
    private UUID followeeId;

    /** For JPA only. */
    protected UserFollow() {}
}
