package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The analysis of SCR-10 and SCR-11 for one pair: the indicators, nearest support and resistance and component scores
 * of the latest closed candle of the requested timeframe, and the setup score of the caller's preset, read on the
 * preset's own timeframe, which it names. A value that cannot be computed yet is {@code null}, never a guess.
 *
 * <p>The component scores are part of a plan ({@code SCORE_COMPONENTS}). On a plan without them both component
 * blocks are {@code null} and {@code componentsLocked} is {@code true}, so a client shows them with a lock rather than
 * as insufficient data; the total score, the preset and the rest are answered on every plan.
 *
 * <p>Rule: UC-10, UC-11, BR-12, BR-13, BR-14, BR-62; SRS 3.3.2, CR-08; TECHNICAL_DESIGN 7.4 and 8; D-53, D-60.
 *
 * @param symbol the symbol
 * @param market {@code SPOT} or {@code FUTURES}
 * @param timeframe the requested timeframe
 * @param openTime the open time of its latest analysed candle, or {@code null} when none is stored
 * @param calculatedAt when that row was computed, or {@code null}
 * @param indicators the indicators of that candle
 * @param nearestSupport the nearest swing low below the close, or {@code null}
 * @param nearestResistance the nearest swing high above the close, or {@code null}
 * @param componentsLocked whether the caller's plan leaves out the component scores, which are then {@code null}
 * @param components the component scores of that candle, or {@code null} when locked
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
        boolean componentsLocked,
        ComponentScoresResponse components,
        SetupScoreResponse setupScore) {}
