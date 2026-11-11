package com.cryptopilot.journal.dto.response;

import java.math.BigDecimal;

/**
 * The metrics of the full dashboard. A metric with nothing to average, or a zero to divide by, is {@code null}; the
 * learning metrics stay {@code null} on trades that have no learning data.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-42, BR-62 ({@code ADVANCED_PERFORMANCE}), BR-70; D-58, D-97, D-111.
 *
 * @param averageWin the mean net P/L of the wins
 * @param averageLoss the mean net P/L of the losses, negative
 * @param profitFactor gross profit / gross loss; {@code null} without losses, 0 without wins
 * @param averagePlannedRiskReward the mean reward/risk of the trades with both a stop loss and a take profit
 * @param averageRealizedR the mean of net P/L / risk, which is also the expectancy in R
 * @param tradesWithR the trades counted in {@code averageRealizedR}; one without a stop loss is not
 * @param largestWin the highest net P/L among the wins
 * @param largestLoss the lowest net P/L among the losses
 * @param maxConsecutiveLosses the longest run of losses in exit order; any other result ends a run
 * @param maxDrawdown the deepest fall of the cumulative net P/L below its peak, from a start at 0, as a positive amount
 * @param averageEntryDeviationR the mean entry deviation, + worse than planned
 * @param costOfLateEntryR the sum of the entry deviations
 * @param averageMaeR the mean maximum adverse excursion in R
 * @param averageMfeR the mean maximum favorable excursion in R
 */
public record PerformanceAdvancedResponse(
        BigDecimal averageWin,
        BigDecimal averageLoss,
        BigDecimal profitFactor,
        BigDecimal averagePlannedRiskReward,
        BigDecimal averageRealizedR,
        long tradesWithR,
        BigDecimal largestWin,
        BigDecimal largestLoss,
        long maxConsecutiveLosses,
        BigDecimal maxDrawdown,
        BigDecimal averageEntryDeviationR,
        BigDecimal costOfLateEntryR,
        BigDecimal averageMaeR,
        BigDecimal averageMfeR) {}
