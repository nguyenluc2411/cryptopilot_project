package com.cryptopilot.market;

/**
 * Receives the futures mark prices of every streamed pair, once a second each. The {@code market} module declares it
 * and calls every bean that implements it; the matching engine of the {@code trading} module is one, so
 * {@code market} never depends on {@code trading}.
 *
 * <p>Called on a stream reader's thread, in the order the exchange sent the prices of a pair: an implementation must
 * not block, and what it throws is logged and does not stop the stream.
 *
 * <p>Rule: NSF-03, NSF-07; TECHNICAL_DESIGN 7.7.
 */
public interface MarkPriceListener {

    /** One mark price of a futures pair. */
    void onMarkPrice(MarkPrice markPrice);
}
