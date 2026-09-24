package com.cryptopilot.market;

/**
 * The two markets a pair can be traded on in this system: Binance Spot and USDⓈ-M perpetual futures.
 *
 * <p>A value object published at the module root, because it is part of how other modules ask the market
 * module anything — a plan, a watch and a candle are all "this pair, on this market". The constants are
 * the values of every {@code market_type} check constraint in the schema, spelled the same way.
 *
 * <p>A pair is one row whatever its markets (the logical model's {@code CRYPTO_PAIR}); the market is part
 * of the identity of everything that depends on the pair — {@code (pair_id, market_type)} — rather than of
 * the pair itself.
 *
 * <p>Rule: BR-07; SRS 3.1.5.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 (a value object is
 * defined by its value alone and has no identity of its own).
 */
public enum MarketType {

    /** Binance Spot. */
    SPOT,

    /** Binance USDⓈ-M perpetual futures. */
    FUTURES
}
