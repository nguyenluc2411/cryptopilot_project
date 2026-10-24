package com.cryptopilot.paper.model.enums;

/**
 * Where the USDT rate of a fill's quote coin came from. The names are the values
 * {@code order_fill.quote_usdt_price_source} accepts.
 *
 * <p>Rule: TR-13; D-96.
 */
public enum UsdtRateSource {

    /** The quote coin is USDT. */
    ONE,

    /** The current last price of the quote coin's USDT pair. */
    LIVE,

    /** The close of the latest closed 1-minute candle of the quote coin's USDT pair, while recent enough. */
    CANDLE_1M,

    /** No rate was known: the coin the fill brought gets no known cost. */
    NONE
}
