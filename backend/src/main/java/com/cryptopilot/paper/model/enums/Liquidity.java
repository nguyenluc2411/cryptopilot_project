package com.cryptopilot.paper.model.enums;

/**
 * Whether a fill added liquidity or took it, which decides its fee rate. The names are the values
 * {@code paper_fill.liquidity} accepts.
 *
 * <p>Rule: TR-02.
 */
public enum Liquidity {

    /** A limit order that waited in the book and was reached by the price. */
    MAKER,

    /** An order that executed on arrival, at the current price. */
    TAKER
}
