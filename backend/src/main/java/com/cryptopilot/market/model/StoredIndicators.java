package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code technical_indicator}: the indicators of one closed candle rounded to 10 places, its nearest support
 * and resistance, and its component scores. No setup score is stored; it is read from the components under a preset.
 *
 * <p>Rule: NSF-05; BR-12, BR-13, BR-14; TECHNICAL_DESIGN 5.4 and 7.4; D-53 (rule 3).
 *
 * @param pairId the pair
 * @param openTime the open time of the candle
 * @param sma20 SMA20, or {@code null} during warm-up
 * @param ema20 EMA20, or {@code null} during warm-up
 * @param ema50 EMA50, or {@code null} during warm-up
 * @param ema200 EMA200, or {@code null} during warm-up
 * @param rsi14 RSI14, or {@code null} during warm-up
 * @param macdLine the MACD line, or {@code null} during warm-up
 * @param macdSignal the MACD signal, or {@code null} during warm-up
 * @param macdHistogram the MACD histogram, or {@code null} during warm-up
 * @param bbUpper the upper Bollinger band, or {@code null} during warm-up
 * @param bbMiddle the middle Bollinger band, or {@code null} during warm-up
 * @param bbLower the lower Bollinger band, or {@code null} during warm-up
 * @param volumeSma20 the volume SMA20, or {@code null} during warm-up
 * @param nearestSupport the nearest swing low below the close, or {@code null} when there is none
 * @param nearestResistance the nearest swing high above the close, or {@code null} when there is none
 * @param components the component scores, which also name the market, timeframe and formula version
 * @param calculatedAt when the row was computed
 */
public record StoredIndicators(
        UUID pairId,
        Instant openTime,
        BigDecimal sma20,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal ema200,
        BigDecimal rsi14,
        BigDecimal macdLine,
        BigDecimal macdSignal,
        BigDecimal macdHistogram,
        BigDecimal bbUpper,
        BigDecimal bbMiddle,
        BigDecimal bbLower,
        BigDecimal volumeSma20,
        BigDecimal nearestSupport,
        BigDecimal nearestResistance,
        ComponentScores components,
        Instant calculatedAt) {

    public StoredIndicators {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
    }
}
