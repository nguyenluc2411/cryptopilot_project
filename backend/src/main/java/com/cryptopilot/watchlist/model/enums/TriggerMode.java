package com.cryptopilot.watchlist.model.enums;

/**
 * How often an alert may fire once its condition is met. Stored by name in {@code alert.trigger_mode}.
 *
 * <p>Rule: BR-19.
 */
public enum TriggerMode {

    /** Fires once, then the alert is TRIGGERED. */
    ONCE,

    /** Fires at most once per candle of the alert's timeframe; 1h for a PRICE alert. */
    ONCE_PER_BAR,

    /** Fires each time the condition becomes true, never more often than the cooldown. */
    EVERY_TIME
}
