package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.Objects;
import java.util.UUID;

/**
 * An administrator brought a pair into service on a market (UC-45): its status is ACTIVE and the market's switch is
 * on, which it was not before. Published after the change commits, so whoever hears it reads the pair as saved.
 * NSF-03 opens the pair's streams and NSF-02 stores its candles at once, rather than at the next refresh of the
 * streams (up to 5 minutes) and the next hourly backfill (up to an hour): a pair the administrator has just turned on
 * shows its price, order book and chart within seconds.
 *
 * <p>Rule: UC-45; BR-07; NSF-02, NSF-03.
 *
 * @param market the market the pair is now available on
 */
public record PairMarketEnabled(UUID pairId, String symbol, MarketType market) {

    public PairMarketEnabled {
        Objects.requireNonNull(pairId, "pairId must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(market, "market must not be null");
    }
}
