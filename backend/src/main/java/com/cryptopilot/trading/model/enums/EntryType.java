package com.cryptopilot.trading.model.enums;

/**
 * How a plan enters its position. The names are the values {@code trading_plan.entry_type} accepts.
 *
 * <p>Rule: BR-33; SRS 3.5.1.
 */
public enum EntryType {

    /** Waits until the price reaches the entry price. */
    LIMIT,

    /** Filled at the last price when the plan is activated. */
    MARKET
}
