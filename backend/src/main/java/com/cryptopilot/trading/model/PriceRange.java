package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The prices a pair traded at in one update: the low and high of the forming 1-minute candle so far (ADR-011).
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; D-09.
 *
 * @param market the market
 * @param pairId the pair
 * @param low the lowest price traded
 * @param high the highest price traded, not below {@code low}
 * @param at when the exchange produced the update
 */
public record PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant at) {

    public PriceRange {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(at, "at");
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low " + low + " is above high " + high);
        }
    }
}
