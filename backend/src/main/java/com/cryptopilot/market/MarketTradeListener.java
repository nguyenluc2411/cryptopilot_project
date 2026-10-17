package com.cryptopilot.market;

/**
 * Receives the aggregated trades of every pair streamed with the trading streams (TR-03). The {@code market} module
 * declares it and calls every bean that implements it; the paper matching engine is one, so {@code market} never
 * depends on {@code paper}.
 *
 * <p>Called on a stream reader's thread, in the order the exchange sent the trades of a pair: an implementation must
 * not block, and what it throws is logged and does not stop the stream.
 *
 * <p>Rule: TR-03, TR-05; plan section 11.1.
 */
public interface MarketTradeListener {

    void onTrade(MarketTrade trade);
}
