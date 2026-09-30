package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscription packages of every plan tier, and the packages a Trader's subscriptions hold.
 *
 * <p>Rule: BR-62; SRS v1.1 entity 34, UC-53, SRS 3.10.4; D-59.
 */
public interface SubscriptionPackageRepository extends Repository<SubscriptionPackage, UUID> {

    SubscriptionPackage save(SubscriptionPackage subscriptionPackage);

    @Transactional(readOnly = true)
    Optional<SubscriptionPackage> findById(UUID packageId);

    /** The package with this code, e.g. {@code PRO_MONTHLY}, or empty. */
    @Transactional(readOnly = true)
    Optional<SubscriptionPackage> findByPackageCode(String packageCode);

    /** The packages of a tier; FREE has exactly one ({@code uq_subscription_package_one_free}). */
    @Transactional(readOnly = true)
    List<SubscriptionPackage> findByTier(PlanTier tier);

    /** The packages still on offer ({@code is_active}), lowest tier first. */
    @Transactional(readOnly = true)
    List<SubscriptionPackage> findAllByActiveTrueOrderByTierRankAsc();

    /**
     * The packages of the Trader's subscriptions that are ACTIVE and running at {@code now} ({@code start_at <= now <
     * end_at}), highest tier first. Read from the instants, not only the status, so a subscription past its end is
     * not held whether or not the expiry job has run yet (SRS 3.10.4).
     */
    @Transactional(readOnly = true)
    @Query(value = """
                    select p.*
                      from user_subscription s
                      join subscription_order o on o.order_id = s.order_id
                      join subscription_package p on p.package_id = o.package_id
                     where o.user_id = :userId
                       and s.subscription_status = 'ACTIVE'
                       and s.start_at <= :now
                       and s.end_at > :now
                     order by p.tier_rank desc""", nativeQuery = true)
    List<SubscriptionPackage> findHeldAt(@Param("userId") UUID userId, @Param("now") Instant now);
}
