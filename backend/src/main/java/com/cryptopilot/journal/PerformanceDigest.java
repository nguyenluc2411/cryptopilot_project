package com.cryptopilot.journal;

import java.math.BigDecimal;

/**
 * A Trader's performance as aggregates only (T-074, D-111): no trade, no pair, no note, no capital and no id, so it
 * can go with a question to the LLM Provider (BR-51). A metric with nothing to average, or a zero to divide by, is
 * {@code null}; the learning metrics stay {@code null} while no trade carries learning data.
 *
 * <p>Rule: BR-38, BR-42, BR-51, BR-70; SRS 3.6.3; D-58, D-97, D-111.
 *
 * @param trades the closed simulated trades counted
 * @param wins trades with a net P/L above 0
 * @param losses trades with a net P/L below 0
 * @param breakeven trades with a net P/L of exactly 0
 * @param winRate {@code wins / (wins + losses)} from 0 to 1; {@code null} without a win or a loss
 * @param totalNetPnl the sum of the net P/L
 * @param totalFees the sum of the fees
 * @param totalFunding the sum of the signed funding, + received, − paid
 * @param provisionalTrades trades whose funding may still change their net P/L
 * @param profitFactor gross profit / gross loss; {@code null} without losses, 0 without wins
 * @param averageRealizedR the mean R-multiple (net P/L / planned risk), which is also the expectancy in R
 * @param tradesWithR the trades counted in {@code averageRealizedR}; one without a stop loss is not
 * @param averageEntryDeviationR the mean entry deviation in R, + worse than planned
 * @param costOfLateEntryR the sum of the entry deviations in R
 * @param averageMaeR the mean maximum adverse excursion in R
 * @param averageMfeR the mean maximum favorable excursion in R
 */
public record PerformanceDigest(
        long trades,
        long wins,
        long losses,
        long breakeven,
        BigDecimal winRate,
        BigDecimal totalNetPnl,
        BigDecimal totalFees,
        BigDecimal totalFunding,
        long provisionalTrades,
        BigDecimal profitFactor,
        BigDecimal averageRealizedR,
        long tradesWithR,
        BigDecimal averageEntryDeviationR,
        BigDecimal costOfLateEntryR,
        BigDecimal averageMaeR,
        BigDecimal averageMfeR) {}
