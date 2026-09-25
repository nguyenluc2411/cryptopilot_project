package com.cryptopilot.market.dto.response;

import java.time.Instant;

/**
 * The statistics of a Spot pair (SCR-01, SCR-10 market statistics).
 *
 * <p>Rule: UC-09; SRS 3.3.1, 3.3.2.
 *
 * @param symbol the symbol
 * @param serverTime the server's instant when it answered, against which a client measures {@code ticker.asOf}
 * @param ticker the latest stored ticker, or absent when none is stored yet
 */
public record SpotStatsResponse(String symbol, Instant serverTime, SpotTickerResponse ticker) {}
