package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest stored Spot ticker of a pair, with the instant it was stored for, so a client can tell its age.
 *
 * <p>Rule: UC-09; NSF-03; SRS 3.3.1.
 *
 * @param asOf the minute the values were stored for
 * @param lastPrice the last traded price
 * @param bestBid the best bid
 * @param bestAsk the best ask
 * @param high24h the 24-hour high
 * @param low24h the 24-hour low
 * @param changePercent24h the 24-hour change in percent
 * @param baseVolume24h the 24-hour volume in the base asset
 * @param quoteVolume24h the 24-hour volume in the quote asset
 */
public record SpotTickerResponse(
        Instant asOf,
        BigDecimal lastPrice,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal high24h,
        BigDecimal low24h,
        BigDecimal changePercent24h,
        BigDecimal baseVolume24h,
        BigDecimal quoteVolume24h) {}
