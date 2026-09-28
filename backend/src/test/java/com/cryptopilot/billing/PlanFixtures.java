package com.cryptopilot.billing;

import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The five packages of D-58 (SRS v1.1 Table 3.1), built through the entity so a test reads the same rows the seed of
 * T-007 will write. Tests state the expected values themselves; this class only stores the packages.
 */
public final class PlanFixtures {

    public static final PlanEntitlements FREE =
            new PlanEntitlements(false, false, 3, 5, 3, false, false, false, false, 0, false, false);
    public static final PlanEntitlements PRO =
            new PlanEntitlements(true, true, null, 50, 20, true, true, true, true, 30, false, false);
    public static final PlanEntitlements PREMIUM =
            new PlanEntitlements(true, true, null, 100, 50, true, true, true, true, 100, true, true);

    private PlanFixtures() {}

    /**
     * Removes the packages the production seed (V11) wrote, so a test can store its own under the same codes and the
     * single FREE package. Call it inside the test's transaction: the rollback puts the seed back.
     */
    public static void removeSeeded(JdbcClient jdbc) {
        jdbc.sql("delete from subscription_package").update();
    }

    /** Stores FREE, PRO_MONTHLY, PRO_YEARLY, PREMIUM_MONTHLY and PREMIUM_YEARLY. */
    public static List<SubscriptionPackage> storeAll(SubscriptionPackageRepository packages) {
        return List.of(
                packages.save(SubscriptionPackage.free("FREE", "Free", FREE)),
                packages.save(SubscriptionPackage.paid(
                        "PRO_MONTHLY", "Pro monthly", PlanTier.PRO, new BigDecimal("99000"), 30, PRO)),
                packages.save(SubscriptionPackage.paid(
                        "PRO_YEARLY", "Pro yearly", PlanTier.PRO, new BigDecimal("990000"), 365, PRO)),
                packages.save(SubscriptionPackage.paid(
                        "PREMIUM_MONTHLY", "Premium monthly", PlanTier.PREMIUM, new BigDecimal("199000"), 30, PREMIUM)),
                packages.save(SubscriptionPackage.paid(
                        "PREMIUM_YEARLY",
                        "Premium yearly",
                        PlanTier.PREMIUM,
                        new BigDecimal("1990000"),
                        365,
                        PREMIUM)));
    }
}
