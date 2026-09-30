package com.cryptopilot.billing.model.enums;

/**
 * The plan tier of a subscription package, lowest first. The constants are the values of the
 * {@code ck_subscription_package_tier} check constraint, spelled the same way, and each rank is the {@code tier_rank}
 * that {@code ck_subscription_package_tier_rank} pairs with it.
 *
 * <p>Rule: BR-62; SRS v1.1 entity 34; D-58, D-59.
 * <p>Reference: Date, C. J. (2003). <i>An Introduction to Database Systems</i> (8th ed.). Addison-Wesley, ch. 9
 * (integrity: a domain restricts a column to its legal values).
 */
public enum PlanTier {

    /** The plan of a Trader without an ACTIVE subscription; never sold. */
    FREE(0),

    /** The first paid tier. */
    PRO(1),

    /** The highest paid tier. */
    PREMIUM(2);

    private final int rank;

    PlanTier(int rank) {
        this.rank = rank;
    }

    /** The position of the tier, lowest 0; a higher rank is an upgrade (BR-55, BR-63). */
    public int rank() {
        return rank;
    }
}
