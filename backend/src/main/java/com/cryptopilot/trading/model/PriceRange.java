package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The prices a pair traded at in one update, the low and high of the forming 1-minute candle so far (ADR-011), or in
 * several updates merged while the matching engine was behind.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; D-09.
 *
 * @param market the market
 * @param pairId the pair
 * @param low the lowest price traded
 * @param high the highest price traded, not below {@code low}
 * @param from when the earliest update in the range was produced; equal to {@code at} for a single update
 * @param at when the latest update in the range was produced
 */
public record PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant from, Instant at) {

    public PriceRange {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(at, "at");
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low " + low + " is above high " + high);
        }
        if (from.isAfter(at)) {
            throw new IllegalArgumentException("from " + from + " is after at " + at);
        }
    }

    /** One update: its time is both the earliest and the latest. */
    public PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant at) {
        this(market, pairId, low, high, at, at);
    }
}
