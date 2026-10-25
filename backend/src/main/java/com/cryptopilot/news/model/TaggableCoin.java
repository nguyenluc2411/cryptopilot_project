package com.cryptopilot.news.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A coin an article can be tagged with: a listed coin of the market module, with the names it is written as in news
 * (NSF-10).
 *
 * @param coinId the coin's key
 * @param symbol the exchange symbol, upper case, e.g. {@code BTC}
 * @param names what outlets call it, e.g. {@code Bitcoin}; never the symbol itself
 */
public record TaggableCoin(UUID coinId, String symbol, List<String> names) {

    public TaggableCoin {
        Objects.requireNonNull(coinId, "coinId must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        names = List.copyOf(names);
    }
}
