package com.cryptopilot.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.PlanEntitlements;
import com.cryptopilot.billing.PlanFixtures;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
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
 * <p>Every test rolls back. A package a test writes carries a {@code TEST_} code; the seeded packages are only read.
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

    @Autowired
    private SubscriptionPackageRepository packages;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void BR62_aPaidPackage_survivesAWriteAndAReadWithEveryColumnIntact() {
        String code = PlanFixtures.testCode("PREMIUM_YEARLY");
        SubscriptionPackage saved = packages.save(SubscriptionPackage.paid(
                code, "Premium yearly", PlanTier.PREMIUM, new BigDecimal("1990000"), 365, PREMIUM_LIMITS));
        entityManager.flush();
        entityManager.clear();

        SubscriptionPackage read = packages.findByPackageCode(code).orElseThrow();
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

    /** The schema admits one FREE package, so the FREE mapping is read on the seeded one. */
    @Test
    void BR62_theSeededFreePackage_readsNoDurationAndIsFoundById() {
        UUID seeded = jdbc.sql("select package_id from subscription_package where tier = 'FREE'")
                .query(UUID.class)
                .single();

        SubscriptionPackage read = packages.findById(seeded).orElseThrow();
        assertThat(read.getPackageCode()).isEqualTo("FREE");
        assertThat(read.getTier()).isEqualTo(PlanTier.FREE);
        assertThat(read.getTierRank()).isZero();
        assertThat(read.getDurationDays()).isNull();
        assertThat(read.getPriceAmount()).isZero();
        assertThat(read.isPurchasable()).isFalse();
        assertThat(read.entitlements()).isEqualTo(PlanFixtures.FREE);
    }

    /** Every seeded package reads back with the entitlements of its tier, which the other billing tests rely on. */
    @Test
    void BR62_theSeededPackages_readTheEntitlementsOfTheirTier() {
        Map<PlanTier, PlanEntitlements> byTier = Map.of(
                PlanTier.FREE, PlanFixtures.FREE,
                PlanTier.PRO, PlanFixtures.PRO,
                PlanTier.PREMIUM, PlanFixtures.PREMIUM);

        for (String code : PlanFixtures.SEEDED_CODES) {
            SubscriptionPackage read = packages.findByPackageCode(code).orElseThrow();
            assertThat(read.entitlements()).as(code).isEqualTo(byTier.get(read.getTier()));
        }
    }
}
