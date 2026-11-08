package com.cryptopilot.billing.model.enums;

/**
 * Where a subscription order stands. The constants are the values of {@code ck_subscription_order_status}.
 *
 * <p>An order is created PENDING and settled once (BR-53): PAID when the gateway confirms the full amount with a valid
 * signature, FAILED when it reports the payment failed or was cancelled, EXPIRED when nobody paid within
 * {@code ORDER_EXPIRY_MINUTES} (BR-54). A success that arrives after the order expired is still honoured (A-03).
 *
 * <p>Rule: BR-53, BR-54; SRS v1.1 entity 35; TECHNICAL_DESIGN 7.11; A-03.
 */
public enum OrderStatus {

    /** Created, waiting for the gateway. */
    PENDING,

    /** Paid; it created or changed the Trader's subscription, or was flagged for an administrator. */
    PAID,

    /** The gateway reported the payment failed or was cancelled. */
    FAILED,

    /** Nobody paid in time. */
    EXPIRED
}
