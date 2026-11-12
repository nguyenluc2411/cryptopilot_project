package com.cryptopilot.billing.model.enums;

/**
 * Where a subscription stands. The constants are the values of {@code ck_user_subscription_status}.
 *
 * <p>A Trader holds at most one ACTIVE subscription ({@code uq_user_subscription_one_active}). A renewal or an upgrade
 * marks the one it replaces REPLACED and carries its unused value over; there is no SCHEDULED subscription.
 *
 * <p>Rule: BR-55, BR-63 (amended); SRS v1.1 entity 36; NSF-14.
 */
public enum SubscriptionStatus {

    /** The subscription the Trader holds now. */
    ACTIVE,

    /** Its period ended. */
    EXPIRED,

    /** Ended by an administrator. */
    CANCELLED,

    /** Replaced by a renewal or an upgrade, which carried its unused value over. */
    REPLACED
}
