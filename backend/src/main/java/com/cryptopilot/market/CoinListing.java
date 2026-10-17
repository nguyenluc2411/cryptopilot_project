package com.cryptopilot.market;

import java.util.Objects;
import java.util.UUID;

/**
 * A coin as other modules name it: the asset a balance is held in, e.g. {@code USDT} or {@code BTC}.
 *
 * <p>Rule: SRS 3.1.5; TR-04.
 *
 * @param coinId the coin
 * @param symbol the exchange symbol of the asset, e.g. {@code BTC}
 * @param name the display name
 */
public record CoinListing(UUID coinId, String symbol, String name) {

    public CoinListing {
        Objects.requireNonNull(coinId, "coinId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(name, "name");
    }
}
