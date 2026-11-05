package com.cryptopilot.journal.model;

import java.math.BigDecimal;

/**
 * The metrics of every plan with the ones of the full dashboard, read in one statement so they describe the same
 * trades. A metric with nothing to average, or a zero to divide by, is {@code null}.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-42, BR-62, BR-70; D-58, D-97, D-111.
 *
 * @param counts the metrics of every plan
 * @param averageWin the mean net P/L of the wins
 * @param averageLoss the mean net P/L of the losses, negative
 * @param profitFactor gross profit / gross loss
 * @param averagePlannedRiskReward the mean reward/risk of the trades with both levels
 * @param averageRealizedR the mean realized R, which is also the expectancy in R
 * @param tradesWithR the trades with a risk to measure R against
 * @param largestWin the highest net P/L among the wins
 * @param largestLoss the lowest net P/L among the losses
 * @param maxConsecutiveLosses the longest run of losses in exit order, 0 without losses
 * @param maxDrawdown the deepest fall of the cumulative net P/L below its peak, from a start at 0
 * @param averageEntryDeviationR the mean entry deviation
 * @param costOfLateEntryR the sum of the entry deviations
 * @param averageMaeR the mean maximum adverse excursion in R
 * @param averageMfeR the mean maximum favorable excursion in R
 */
public record PerformanceDetail(
        PerformanceCounts counts,
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
