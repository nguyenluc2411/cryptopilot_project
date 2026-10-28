package com.cryptopilot.paper.model.enums;

/**
 * Whether a paper Futures position still has a size. The names are the values {@code futures_position.position_status}
 * accepts.
 *
 * <p>Rule: TR-16; D-127.
 */
public enum PositionStatus {

    /** The position has a size. */
    OPEN,

    /** The position is back to zero; a later fill opens a new one. */
    CLOSED
}
