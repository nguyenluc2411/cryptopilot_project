package com.cryptopilot.billing.model.enums;

/**
 * What an order does to the Trader's subscription, decided from the package bought and the ACTIVE subscription the
 * Trader holds. The constants are the values of {@code ck_subscription_order_type}.
 *
 * <p>There is no DOWNGRADE: a package of a lower tier than the one held is refused when the order is created (BR-55
 * and BR-63 as amended, D-147).
 *
 * <p>Rule: BR-55, BR-63 (amended); SRS v1.1 SCR-30.
 */
public enum OrderType {

    /** The Trader holds no ACTIVE subscription; the new one starts when the order is paid. */
    NEW,

    /** The same tier as the one held; the new period is added after the current end. */
    RENEW,

    /** A higher tier; it starts when paid and the unused value of the old one is carried over as days. */
    UPGRADE
}
