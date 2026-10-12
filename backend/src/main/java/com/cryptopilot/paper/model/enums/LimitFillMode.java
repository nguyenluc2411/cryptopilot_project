package com.cryptopilot.paper.model.enums;

/**
 * When a waiting LIMIT order is reached by the price (Q-T8).
 *
 * <p>A price through the order's own reaches it in every mode: a BUY at 80000 fills on a trade at 79999, a SELL at
 * 80000 on one at 80001. The modes differ on a trade at exactly the order's price.
 *
 * <p>Rule: TR-05; Q-T8; D-86.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 6 (order precedence rules: price priority, then time precedence, as Binance matches).
 */
public enum LimitFillMode {

    /**
     * As Binance does (price-time priority): the order joins the back of the queue at its price, behind the quantity
     * the exchange's order book showed there, and fills once the trades at that price have consumed that quantity.
     * A trade at its price from the other side fills it at once: a taker BUY at 80000 means no bid was left there, so
     * a BUY waiting at 80000 would have been first. Candles carry no volume by price and fill as {@link #CROSS} does.
     */
    QUEUE,

    /** Only a price through the order's own fills it: the queue at its price is never assumed reached. */
    CROSS,

    /** A price equal to the order's own also fills it, as if it were first in the queue there. */
    TOUCH
}
