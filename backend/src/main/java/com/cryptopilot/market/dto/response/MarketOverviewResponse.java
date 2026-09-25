package com.cryptopilot.market.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * The payload of {@code /topic/overview.{market}}: the latest ticker of every pair streamed on the market, for the
 * market lists of SCR-01 and SCR-09, each with its own source instant.
 *
 * <p>Rule: NSF-03; SRS 3.3.1; TECHNICAL_DESIGN 9; D-51.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param serverTime the server's instant when it sent the overview
 * @param tickers the latest update of each pair, by symbol
 */
public record MarketOverviewResponse(String market, Instant serverTime, List<TickerUpdateResponse> tickers) {}
