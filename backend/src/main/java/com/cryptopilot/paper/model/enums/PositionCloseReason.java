package com.cryptopilot.paper.model.enums;

/**
 * Why a paper Futures position closed. The names are the values {@code futures_position.close_reason} accepts.
 *
 * <p>Rule: TR-16, TR-22; D-127.
 */
public enum PositionCloseReason {

    /** An order of the Trader closed it. */
    MANUAL,

    /** A take profit closed it. */
    TAKE_PROFIT,

    /** A stop loss closed it. */
    STOP_LOSS,

    /** The engine liquidated it. */
    LIQUIDATION,

    /** A reduce-only order took the last of it. */
    REDUCE,

    /** The system closed it, e.g. when the pair stopped trading. */
    SYSTEM
}
