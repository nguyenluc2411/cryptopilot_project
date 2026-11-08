package com.cryptopilot.billing.model.enums;

/**
 * The payment gateway an order is paid through. The constants are the values of {@code ck_subscription_order_gateway}.
 *
 * <p>VNPAY is a legacy value: orders written before SePay replaced it still name it, and no new order may use it
 * ({@link #acceptsNewOrders()}).
 *
 * <p>Rule: TECHNICAL_DESIGN 7.11; D-146 (VNPay replaced by SePay).
 */
public enum GatewayCode {

    /** Replaced by SePay; kept for the orders that already name it. */
    VNPAY(false),

    /** MoMo e-wallet. */
    MOMO(true),

    /** SePay payment gateway. */
    SEPAY(true);

    private final boolean acceptsNewOrders;

    GatewayCode(boolean acceptsNewOrders) {
        this.acceptsNewOrders = acceptsNewOrders;
    }

    /** Whether a new order may be paid through this gateway at all, before any administrator switch. */
    public boolean acceptsNewOrders() {
        return acceptsNewOrders;
    }
}
