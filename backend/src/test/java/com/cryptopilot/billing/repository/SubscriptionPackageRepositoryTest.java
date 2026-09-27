package com.cryptopilot.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.PlanEntitlements;
import com.cryptopilot.billing.PlanTier;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The package mapping against the schema V10 migrated: every column written and read back intact, the FREE row with
 * its null duration and a paid row with its null maximum, and the rank stored from the tier. Validation of the
 * mapping itself happens at start-up ({@code ddl-auto=validate}); this proves what validation cannot, that no two
 * columns are swapped.
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: BR-62; SRS v1.1 entity 34; D-59.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class SubscriptionPackageRepositoryTest {

    /** Every switch different from its neighbours, so a swapped column cannot read back equal. */
    private static final PlanEntitlements PREMIUM_LIMITS =
            new PlanEntitlements(true, false, null, 100, 50, true, false, true, false, 100, true, true);

    private static final PlanEntitlements FREE_LIMITS =
            new PlanEntitlements(false, true, 3, 5, 4, false, true, false, true, 0, false, false);

    @Autowired
    private SubscriptionPackageRepository packages;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void BR62_aPaidPackage_survivesAWriteAndAReadWithEveryColumnIntact() {
        SubscriptionPackage saved = packages.save(SubscriptionPackage.paid(
                "PREMIUM_YEARLY", "Premium yearly", PlanTier.PREMIUM, new BigDecimal("1990000"), 365, PREMIUM_LIMITS));
        entityManager.flush();
        entityManager.clear();

        SubscriptionPackage read = packages.findByPackageCode("PREMIUM_YEARLY").orElseThrow();
        assertThat(read.getId()).isEqualTo(saved.getId());
        assertThat(read.getPackageName()).isEqualTo("Premium yearly");
        assertThat(read.getTier()).isEqualTo(PlanTier.PREMIUM);
        assertThat(read.getTierRank()).isEqualTo((short) 2);
        assertThat(read.getPriceAmount()).isEqualByComparingTo("1990000");
        assertThat(read.getDurationDays()).isEqualTo(365);
        assertThat(read.isPurchasable()).isTrue();
        assertThat(read.entitlements()).isEqualTo(PREMIUM_LIMITS);
        assertThat(jdbc.sql("select tier from subscription_package where package_id = ?")
                        .param(saved.getId())
                        .query(String.class)
                        .single())
                .as("the tier is stored by name, never as an ordinal")
                .isEqualTo("PREMIUM");
    }

    @Test
    void BR62_theFreePackage_storesNoDurationAndIsFoundById() {
        SubscriptionPackage saved = packages.save(SubscriptionPackage.free("FREE", "Free", FREE_LIMITS));
        entityManager.flush();
        entityManager.clear();

        SubscriptionPackage read = packages.findById(saved.getId()).orElseThrow();
        assertThat(read.getDurationDays()).isNull();
        assertThat(read.getPriceAmount()).isZero();
        assertThat(read.isPurchasable()).isFalse();
        assertThat(read.entitlements()).isEqualTo(FREE_LIMITS);
    }
}
