package com.cryptopilot.journal;

import com.cryptopilot.market.ClosedCandleIndicators;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The learning data of one simulated trade as the journal stored it at the fill and while it was open, with the
 * figures derived from it. Any value is {@code null} when it was not known; the derived ones also while the trade is
 * open, or without a positive planned risk.
 *
 * <p>Rule: BR-36, BR-42, BR-69, BR-70; D-97, D-111 (M4), D-112.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (R-multiple);
 * Sweeney, J. (1996). <i>Maximum Adverse Excursion</i>. Wiley (MAE, MFE).
 *
 * @param journalId the journal record
 * @param planId the plan that filled
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param status OPEN or CLOSED
 * @param entryTime when the entry filled
 * @param exitTime when the exit counts; {@code null} while OPEN
 * @param entryPrice the fill price
 * @param plannedEntryPrice the planned entry
 * @param entryDeviationR the deviation of the fill in R; positive is a worse entry
 * @param entryLiquidity MAKER or TAKER
 * @param riskAmount the planned risk amount
 * @param analysisTimeframe the plan's chart timeframe
 * @param setupScorePreset the preset name and version of both scores
 * @param setupScoreAtPlan the setup score at activation
 * @param setupScoreAtFill the setup score at the fill
 * @param setupScoreDropWarned whether the drop warning was raised; {@code null} when nothing was compared
 * @param indicators the indicators of the latest closed candle at the fill
 * @param maxAdversePrice the most adverse price while open
 * @param maxFavorablePrice the most favourable price while open
 * @param netPnl CLOSED only: the net P/L (BR-38)
 * @param rMultiple CLOSED only: net P/L / planned risk
 * @param maeR the maximum adverse excursion in R
 * @param mfeR the maximum favourable excursion in R
 */
public record FillLearning(
        UUID journalId,
        UUID planId,
        UUID pairId,
        MarketType market,
        Direction direction,
        PositionStatus status,
        Instant entryTime,
        Instant exitTime,
        BigDecimal entryPrice,
        BigDecimal plannedEntryPrice,
        BigDecimal entryDeviationR,
        LiquidityRole entryLiquidity,
        BigDecimal riskAmount,
        String analysisTimeframe,
        String setupScorePreset,
        BigDecimal setupScoreAtPlan,
        BigDecimal setupScoreAtFill,
        Boolean setupScoreDropWarned,
        ClosedCandleIndicators indicators,
        BigDecimal maxAdversePrice,
        BigDecimal maxFavorablePrice,
        BigDecimal netPnl,
        BigDecimal rMultiple,
        BigDecimal maeR,
        BigDecimal mfeR) {}
