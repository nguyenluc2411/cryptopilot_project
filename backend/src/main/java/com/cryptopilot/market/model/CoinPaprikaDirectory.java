package com.cryptopilot.market.model;

import com.cryptopilot.market.client.CoinPaprikaCoin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CoinPaprika's coins by Binance symbol: of the tracked, ranked coins with the ticker the best rank by market cap wins,
 * unless configuration names the CoinPaprika id for the symbol (a coin CoinPaprika lists under another ticker, such as
 * Toncoin as {@code GRAM}).
 *
 * <p>Rule: SRS 3.1.5.
 */
public final class CoinPaprikaDirectory {

    private final Map<String, CoinPaprikaCoin> bySymbol = new HashMap<>();
    private final Map<String, CoinPaprikaCoin> byId = new HashMap<>();
    private final Map<String, String> overrides;

    public CoinPaprikaDirectory(List<CoinPaprikaCoin> listed, Map<String, String> overrides) {
        this.overrides = Map.copyOf(overrides);
        for (CoinPaprikaCoin coin : listed) {
            byId.put(coin.id(), coin);
            if (coin.ranked()) {
                bySymbol.merge(coin.symbol(), coin, (a, b) -> a.rank() <= b.rank() ? a : b);
            }
        }
    }

    /** The coin a Binance symbol stands for, or empty. */
    public Optional<CoinPaprikaCoin> find(String symbol) {
        String id = overrides.get(symbol);
        if (id != null) {
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.ofNullable(bySymbol.get(symbol));
    }
}
