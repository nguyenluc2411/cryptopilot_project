package com.cryptopilot.journal.dto.response;

import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The learning data of a simulated trade on SCR-21: what was stored at the fill and while it was open, and the figures
 * derived from it, which are never stored. A value is {@code null} when it was not known; the R figures also without a
 * positive planned risk, and the R-multiple while the trade is open.
 *
 * <p>Rule: BR-36, BR-42, BR-69, BR-70; D-111 (M4), D-112.
 *
 * @param plannedEntryPrice the planned entry
 * @param entryDeviationR the deviation of the fill in R; positive is a worse entry
 * @param entryLiquidity MAKER or TAKER
 * @param riskAmount the planned risk amount
 * @param analysisTimeframe the plan's chart timeframe, the one of the indicators
 * @param setupScorePreset the preset name and version of both scores, e.g. {@code DAY_TRADING v1}
 * @param setupScoreAtPlan the setup score at activation
 * @param setupScoreAtFill the setup score at the fill
 * @param setupScoreDropWarned whether the score fell by at least the warning threshold (MSG55); {@code null} when one
 *     score is unknown, so nothing was compared
 * @param indicators the indicators of the latest closed candle at the fill
 * @param maxAdversePrice the most adverse price while open
 * @param maxFavorablePrice the most favourable price while open
 * @param rMultiple net P/L / planned risk, CLOSED only
 * @param maeR the maximum adverse excursion in R
 * @param mfeR the maximum favourable excursion in R
 */
public record FillLearningResponse(
        BigDecimal plannedEntryPrice,
        BigDecimal entryDeviationR,
        LiquidityRole entryLiquidity,
        BigDecimal riskAmount,
        String analysisTimeframe,
        String setupScorePreset,
        BigDecimal setupScoreAtPlan,
        BigDecimal setupScoreAtFill,
        Boolean setupScoreDropWarned,
        Indicators indicators,
        BigDecimal maxAdversePrice,
        BigDecimal maxFavorablePrice,
        BigDecimal rMultiple,
        BigDecimal maeR,
        BigDecimal mfeR) {

    /**
     * The indicator values at the fill; each {@code null} while its indicator was warming up.
     *
     * @param timeframe the timeframe of the candle
     * @param openTime the open time of the candle
     * @param rsi14 RSI14
     * @param ema20 EMA20
     * @param ema50 EMA50
     * @param ema200 EMA200
     * @param macdLine the MACD line
     * @param macdSignal the MACD signal line
     * @param macdHistogram the MACD histogram
     */
    public record Indicators(
            String timeframe,
            Instant openTime,
            BigDecimal rsi14,
            BigDecimal ema20,
            BigDecimal ema50,
            BigDecimal ema200,
            BigDecimal macdLine,
            BigDecimal macdSignal,
            BigDecimal macdHistogram) {}
}
