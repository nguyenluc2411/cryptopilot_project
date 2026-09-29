package com.cryptopilot.billing;

import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The five packages of D-58 as the production seed (V11) writes them, and the rule for the packages a test writes
 * itself.
 *
 * <p>The seeded packages are a shared fixture that no test changes for good: a test reads them, and whatever it
 * updates is rolled back with its transaction. A test that needs a package of its own writes a fresh one under a code
 * starting with {@link #TEST_CODE_PREFIX}, which the seed never uses, and never a second FREE package, since the seeded
 * one is the only FREE package the schema admits.
 *
 * <p>Rule: BR-62; D-58, D-62.
 *
 * <p>Reference: Meszaros, G. (2007). xUnit Test Patterns: Refactoring Test Code. Addison-Wesley — Fresh Fixture and
 * Shared Fixture (Immutable Shared Fixture).
 */
public final class PlanFixtures {

    /** The prefix of every package code a test writes; no seeded code starts with it. */
    public static final String TEST_CODE_PREFIX = "TEST_";

    /** The codes V11 seeds. */
    public static final List<String> SEEDED_CODES =
            List.of("FREE", "PRO_MONTHLY", "PRO_YEARLY", "PREMIUM_MONTHLY", "PREMIUM_YEARLY");

    /** The entitlements V11 gives each tier; tests compare the seeded rows against them. */
    public static final PlanEntitlements FREE =
            new PlanEntitlements(false, false, 3, 5, 3, false, false, false, false, 0, false, false);

    public static final PlanEntitlements PRO =
            new PlanEntitlements(true, true, null, 50, 20, true, true, true, true, 30, false, false);
    public static final PlanEntitlements PREMIUM =
            new PlanEntitlements(true, true, null, 100, 50, true, true, true, true, 100, true, true);

    private PlanFixtures() {}

    /** The id of each seeded package by its code; fails when the seed has not run. */
    public static Map<String, UUID> seededIds(SubscriptionPackageRepository packages) {
        return SEEDED_CODES.stream()
                .map(code -> packages.findByPackageCode(code)
                        .orElseThrow(() -> new IllegalStateException("the seeded package " + code + " is missing")))
                .collect(Collectors.toMap(SubscriptionPackage::getPackageCode, SubscriptionPackage::getId));
    }

    /** A code for a package the test writes itself. */
    public static String testCode(String name) {
        return TEST_CODE_PREFIX + name;
    }
}
