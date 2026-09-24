package com.cryptopilot.market.repository;

import com.cryptopilot.market.MarketType;
import java.time.Instant;
import java.util.UUID;

/**
 * A hole inside a stored candle series: two consecutive stored candles further apart than one timeframe.
 *
 * <p>Rule: NSF-02, NSF-03; A-33.
 *
 * @param pairId the pair
 * @param market the market
 * @param timeframe the timeframe, e.g. {@code 1h}
 * @param lastBefore the open time of the stored candle before the hole
 * @param firstAfter the open time of the stored candle after the hole
 */
public record StoredGap(UUID pairId, MarketType market, String timeframe, Instant lastBefore, Instant firstAfter) {}
