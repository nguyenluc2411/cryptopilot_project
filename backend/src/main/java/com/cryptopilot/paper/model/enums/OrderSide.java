package com.cryptopilot.paper.model.enums;

/**
 * The side of a paper order or fill. The names are the values {@code trade_order.side} and {@code order_fill.side}
 * accept.
 *
 * <p>Rule: TR-02.
 */
public enum OrderSide {

    /** Buys the base asset with the quote asset. */
    BUY,

    /** Sells the base asset for the quote asset. */
    SELL
}
