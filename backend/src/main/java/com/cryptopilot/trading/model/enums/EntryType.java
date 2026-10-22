package com.cryptopilot.trading.model.enums;

/**
 * How a plan enters its position. The names are the values {@code trading_plan.entry_type} accepts.
 *
 * <p>Rule: BR-33; SRS 3.5.1; D-111.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (limit and stop orders).
 */
public enum EntryType {

    /** Waits until the price reaches the entry price. */
    LIMIT,

    /** Filled at the last price when the plan is activated. */
    MARKET,

    /**
     * A stop-market entry for a breakout: waits until the price reaches the stop price, kept as the entry price (LONG
     * at or above it, SHORT at or below it), then fills as a market order.
     */
    STOP
}
