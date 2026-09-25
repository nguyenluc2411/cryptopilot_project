package com.cryptopilot.market.dto.response;

import java.util.List;

/**
 * The closed candles of one series, oldest first. Only closed candles are stored, so the forming one is never here
 * (BR-08); a client that pages backwards passes the first {@code openTime} it holds as the next {@code to}.
 *
 * <p>Rule: UC-09, BR-08; SRS 3.3.1, 3.3.2.
 *
 * @param symbol the symbol
 * @param market {@code SPOT} or {@code FUTURES}
 * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param candles the candles, oldest first
 */
public record CandlesResponse(String symbol, String market, String timeframe, List<CandleResponse> candles) {}
