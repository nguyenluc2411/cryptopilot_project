package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest Spot snapshot of a pair, as NSF-03 wrote it.
 *
 * <p>Rule: NSF-03; UC-09.
 *
 * @param snapshotTime the minute the values were written for
 * @param lastPrice the last traded price
 * @param bestBid the best bid
 * @param bestAsk the best ask
 * @param high24h the 24-hour high
 * @param low24h the 24-hour low
 * @param changePercent24h the 24-hour change in percent
 * @param baseVolume24h the 24-hour volume in the base asset
 * @param quoteVolume24h the 24-hour volume in the quote asset
 */
public record SpotSnapshot(
        Instant snapshotTime,
        BigDecimal lastPrice,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal high24h,
        BigDecimal low24h,
        BigDecimal changePercent24h,
        BigDecimal baseVolume24h,
        BigDecimal quoteVolume24h) {}
