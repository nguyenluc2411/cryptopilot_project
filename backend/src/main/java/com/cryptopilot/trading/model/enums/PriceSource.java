package com.cryptopilot.trading.model.enums;

/**
 * Which price a price range is made of: the last traded price drives entries, take profit and stop loss; the Futures
 * mark price drives liquidation only (D-111 E2).
 *
 * <p>Rule: NSF-07, BR-35; TECHNICAL_DESIGN 7.7.
 */
public enum PriceSource {

    /** The last traded price, from the {@code kline_1m} stream or closed 1-minute candles. */
    LAST,

    /** The Futures mark price, from the {@code markPrice@1s} stream or closed mark price candles. */
    MARK
}
