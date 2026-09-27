package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.entity.SubscriptionPackage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscription packages of every plan tier.
 *
 * <p>Rule: BR-62; SRS v1.1 entity 34; D-59.
 */
public interface SubscriptionPackageRepository extends Repository<SubscriptionPackage, UUID> {

    SubscriptionPackage save(SubscriptionPackage subscriptionPackage);

    @Transactional(readOnly = true)
    Optional<SubscriptionPackage> findById(UUID packageId);

    /** The package with this code, e.g. {@code PRO_MONTHLY}, or empty. */
    @Transactional(readOnly = true)
    Optional<SubscriptionPackage> findByPackageCode(String packageCode);
}
