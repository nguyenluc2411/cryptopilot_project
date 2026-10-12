package com.cryptopilot.paper.model.enums;

/**
 * When a waiting LIMIT order is reached by the price (Q-T8).
 *
 * <p>Rule: TR-05; Q-T8; D-86.
 */
public enum LimitFillMode {

    /**
     * A BUY fills on a price below its own, a SELL on a price above it: the price went through the level, so the
     * queue ahead of the order there is assumed consumed. Binance-like for an order that joined the back of the queue.
     */
    CROSS,

    /** A price equal to the order's own also fills it. */
    TOUCH
}
