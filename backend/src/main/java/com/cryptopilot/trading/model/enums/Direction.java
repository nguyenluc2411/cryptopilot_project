package com.cryptopilot.trading.model.enums;

/**
 * The side of a trading plan. A Spot plan is always {@link #LONG} (BR-21).
 *
 * <p>Rule: BR-21, BR-22.
 */
public enum Direction {

    /** Profits when the price rises: stop loss below the entry, take profit above it. */
    LONG,

    /** Profits when the price falls: take profit below the entry, stop loss above it. */
    SHORT
}
