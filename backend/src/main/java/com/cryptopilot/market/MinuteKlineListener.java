package com.cryptopilot.market;

/**
 * Receives the {@code kline_1m} updates of every streamed pair. The {@code market} module declares it and calls every
 * bean that implements it; the matching engine of the {@code trading} module is one, so {@code market} never depends
 * on {@code trading}.
 *
 * <p>Called on a stream reader's thread, in the order the exchange sent the updates of a pair: an implementation must
 * not block, and what it throws is logged and does not stop the stream.
 *
 * <p>Rule: NSF-03, NSF-07; TECHNICAL_DESIGN 7.7; D-09; Q-32.
 */
public interface MinuteKlineListener {

    /** One update of a pair's 1-minute candle, forming or closed. */
    void onMinuteKline(MinuteKline kline);
}
