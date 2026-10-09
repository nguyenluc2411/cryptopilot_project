package com.cryptopilot.paper.model.enums;

/**
 * The kind of a paper order. The names are the values {@code paper_order.order_type} accepts; the stop, take profit
 * and trailing kinds are placed by later stages of TR-02, so a request for one is refused for now.
 *
 * <p>Rule: TR-02.
 */
public enum OrderType {

    /** Executes at once at the current price. */
    MARKET,

    /** Waits at its price, or executes at once when the price is already there. */
    LIMIT,

    STOP_MARKET,
    STOP_LIMIT,
    TAKE_PROFIT_MARKET,
    TAKE_PROFIT_LIMIT,
    TRAILING_STOP_MARKET,
    TRAILING_STOP_LIMIT
}
