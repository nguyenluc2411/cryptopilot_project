package com.cryptopilot.trading.model.enums;

/**
 * Why a position closed. The names are the values {@code trading_journal.close_reason} accepts.
 *
 * <p>Rule: BR-35, BR-39; D-97.
 */
public enum CloseReason {

    /** The last price reached the take profit. */
    TAKE_PROFIT,

    /** The last price reached the stop loss. */
    STOP_LOSS,

    /** The Trader closed the position at the last price. */
    MANUAL,

    /** The mark price reached the liquidation price of a Futures position. */
    LIQUIDATION
}
