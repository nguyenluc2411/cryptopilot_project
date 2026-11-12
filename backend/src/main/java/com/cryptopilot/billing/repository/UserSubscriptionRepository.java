package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscriptions paid orders created.
 *
 * <p>Rule: BR-55 as amended by D-147; SRS v1.1 entity 36.
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

    /** The subscription this order opened, or empty when it opened none (SCR-40). */
    @Transactional(readOnly = true)
    Optional<UserSubscription> findByOrderId(UUID orderId);

    /**
     * Writes the pending changes now. A subscription marked REPLACED or EXPIRED is flushed before its successor is
     * inserted: Hibernate inserts before it updates, and {@code uq_user_subscription_one_active} would see two ACTIVE.
     */
    void flush();

    /** The ACTIVE subscriptions past their end, soonest first, one page of them: what the hourly run expires. */
    @Transactional(readOnly = true)
    @Query("""
            select s.id from UserSubscription s
             where s.status = com.cryptopilot.billing.model.enums.SubscriptionStatus.ACTIVE and s.endAt <= :now
             order by s.endAt, s.id""")
    List<UUID> findEndedIds(@Param("now") Instant now, Pageable page);

    /**
     * The ACTIVE subscriptions ending in {@code (now, until]} whose Trader was not told yet, soonest first, one page of
     * them: what the hourly run reminds (NSF-14).
     */
    @Transactional(readOnly = true)
    @Query("""
            select s.id from UserSubscription s
             where s.status = com.cryptopilot.billing.model.enums.SubscriptionStatus.ACTIVE
               and s.remindedAt is null and s.endAt > :now and s.endAt <= :until
             order by s.endAt, s.id""")
    List<UUID> findIdsToRemind(@Param("now") Instant now, @Param("until") Instant until, Pageable page);

    /** The owner of the subscription, read without loading the row, so it can be locked for before it is read. */
    @Transactional(readOnly = true)
    @Query("select s.userId from UserSubscription s where s.id = :id")
    Optional<UUID> findUserIdOf(@Param("id") UUID subscriptionId);
}
