package com.cryptopilot.paper.model.enums;

/**
 * The position an order or a fill belongs to: {@code BOTH} in one-way mode, {@code LONG} or {@code SHORT} in hedge
 * mode. The names are the values {@code trade_order.position_side}, {@code order_fill.position_side} and
 * {@code futures_position.position_side} accept.
 *
 * <p>Rule: TR-14; D-127.
 */
public enum PositionSide {

    /** One-way mode: a single position per pair, long or short. */
    BOTH,

    /** Hedge mode: the long position of the pair. */
    LONG,

    /** Hedge mode: the short position of the pair. */
    SHORT
}
