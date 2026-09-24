package com.cryptopilot.market.client;

/**
 * The two Binance APIs this system reads: Spot and USDⓈ-M perpetual futures.
 *
 * <p>They are separate services with separate hosts and separate request-weight budgets per IP, so the
 * client keeps a separate budget, ban state and circuit breaker for each: a ban on one says nothing
 * about the other.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.1.
 */
public enum BinanceVenue {

    /** {@code api.binance.com}, the Spot market. */
    SPOT,

    /** {@code fapi.binance.com}, USDⓈ-M futures. */
    USD_M_FUTURES
}
