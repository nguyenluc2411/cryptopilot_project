package com.cryptopilot.trading.model.enums;

/**
 * Whether the position of a journal record is still held. The names are the values {@code trading_journal.trade_status}
 * accepts.
 *
 * <p>Rule: BR-34, BR-35; UC-20.
 */
public enum PositionStatus {

    /** Held: the entry filled and no exit has closed it. */
    OPEN,

    /** Closed by a take profit, a stop loss, a manual close or a liquidation. */
    CLOSED
}
