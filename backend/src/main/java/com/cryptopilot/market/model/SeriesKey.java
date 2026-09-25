package com.cryptopilot.market.model;

import com.cryptopilot.market.MarketType;
import java.util.Objects;
import java.util.UUID;

/**
 * One candle series: a pair, a market and a timeframe. The indicators keep one state per series (TECHNICAL_DESIGN 7.2).
 *
 * <p>Rule: BR-08, BR-12.
 *
 * @param pairId the pair
 * @param market the market
 * @param timeframe the timeframe as BR-08 spells it: {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 */
public record SeriesKey(UUID pairId, MarketType market, String timeframe) {

    public SeriesKey {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(timeframe, "timeframe");
    }
}
