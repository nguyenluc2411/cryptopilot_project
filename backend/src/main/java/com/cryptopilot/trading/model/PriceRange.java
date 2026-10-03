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
 * @param from the open time of the 1-minute candle the prices belong to; a fill reached by the range is recorded at
 *     this time, so a live update and the closed candle replayed later give the same fill (D-78)
 * @param at when the latest update in the range was produced
 * @param closed whether the range holds the candle's final low and high: the candle is closed
 */
public record PriceRange(
        MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant from, Instant at, boolean closed) {

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

    /** Updates of a candle still forming. */
    public PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant from, Instant at) {
        this(market, pairId, low, high, from, at, false);
    }

    /** One update whose candle opened at {@code at}, still forming. */
    public PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant at) {
        this(market, pairId, low, high, at, at, false);
    }

    /**
     * This range and another of the same pair as one: the lowest low, the highest high, the earliest and the latest
     * time, closed when either is. Every price either reached is still reached.
     */
    public PriceRange mergedWith(PriceRange other) {
        return new PriceRange(
                market,
                pairId,
                low.min(other.low),
                high.max(other.high),
                from.isBefore(other.from) ? from : other.from,
                at.isAfter(other.at) ? at : other.at,
                closed || other.closed);
    }
}
