package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The analysis of SCR-10 and SCR-11 for one pair: the indicators, nearest support and resistance and component scores
 * of the latest closed candle of the requested timeframe, and the setup score of the caller's preset, read on the
 * preset's own timeframe, which it names. A value that cannot be computed yet is {@code null}, never a guess.
 *
 * <p>Rule: UC-10, UC-11, BR-12, BR-13, BR-14; SRS 3.3.2; TECHNICAL_DESIGN 7.4 and 8; D-53.
 *
 * @param symbol the symbol
 * @param market {@code SPOT} or {@code FUTURES}
 * @param timeframe the requested timeframe
 * @param openTime the open time of its latest analysed candle, or {@code null} when none is stored
 * @param calculatedAt when that row was computed, or {@code null}
 * @param indicators the indicators of that candle
 * @param nearestSupport the nearest swing low below the close, or {@code null}
 * @param nearestResistance the nearest swing high above the close, or {@code null}
 * @param components the component scores of that candle
 * @param setupScore the setup score of the caller's preset
 */
public record AnalysisResponse(
        String symbol,
        String market,
        String timeframe,
        Instant openTime,
        Instant calculatedAt,
        IndicatorsResponse indicators,
        BigDecimal nearestSupport,
        BigDecimal nearestResistance,
        ComponentScoresResponse components,
        SetupScoreResponse setupScore) {}
