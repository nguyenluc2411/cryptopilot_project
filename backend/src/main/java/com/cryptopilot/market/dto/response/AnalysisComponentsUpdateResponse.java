package com.cryptopilot.market.dto.response;

import java.time.Instant;

/**
 * The payload of {@code /topic/analysis.{market}.{symbol}.{tf}.components}: the component scores of one closed candle,
 * with their formula version, for a plan that includes them (D-60).
 *
 * <p>Rule: NSF-05; BR-13, BR-62; SRS CR-08; TECHNICAL_DESIGN 7.4 and 9; D-53, D-126.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param symbol the symbol
 * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param openTime the open time of the closed candle, which identifies it
 * @param components the component scores of that candle
 */
public record AnalysisComponentsUpdateResponse(
        String market, String symbol, String timeframe, Instant openTime, ComponentScoresResponse components) {}
