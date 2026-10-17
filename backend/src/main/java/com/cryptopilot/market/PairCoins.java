package com.cryptopilot.market;

import java.util.Objects;
import java.util.UUID;

/**
 * The two coins of a pair: the base asset an order buys or sells, and the quote asset it is priced and paid in, e.g.
 * {@code BTC} and {@code USDT} for {@code BTCUSDT}. What a paper wallet debits and credits when a Spot order fills.
 *
 * <p>Rule: SRS 3.1.5; TR-02.
 *
 * @param pairId the pair
 * @param base the asset bought or sold
 * @param quote the asset the price is in
 */
public record PairCoins(UUID pairId, CoinListing base, CoinListing quote) {

    public PairCoins {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
    }
}
