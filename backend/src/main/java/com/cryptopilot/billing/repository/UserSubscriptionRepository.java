package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscriptions paid orders created.
 *
 * <p>Rule: BR-55 as amended by D-146; SRS v1.1 entity 36.
 */
public interface UserSubscriptionRepository extends Repository<UserSubscription, UUID> {

    UserSubscription save(UserSubscription subscription);

    @Transactional(readOnly = true)
    Optional<UserSubscription> findById(UUID subscriptionId);

    /**
     * The Trader's subscription in {@code status}; for ACTIVE there is at most one
     * ({@code uq_user_subscription_one_active}). Its period may have ended if the expiry job has not run yet.
     */
    @Transactional(readOnly = true)
    Optional<UserSubscription> findByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    /**
     * Writes the pending changes now. A subscription marked REPLACED or EXPIRED is flushed before its successor is
     * inserted: Hibernate inserts before it updates, and {@code uq_user_subscription_one_active} would see two ACTIVE.
     */
    void flush();
}
