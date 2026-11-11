package com.cryptopilot.journal.model;

import java.math.BigDecimal;

/**
 * The metrics of every plan.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-38, BR-42, BR-62.
 *
 * @param trades the closed trades counted
 * @param wins trades with a net P/L above 0
 * @param losses trades with a net P/L below 0
 * @param breakeven trades with a net P/L of exactly 0
 * @param winRate {@code wins / (wins + losses)} from 0 to 1; {@code null} without a win or a loss
 * @param totalNetPnl the sum of the net P/L, 0 without trades
 * @param provisionalTrades trades whose funding may still change their net P/L
 * @param totalFees the sum of the fees
 * @param totalFunding the sum of the signed funding, + received
 */
public record PerformanceCounts(
        long trades,
        long wins,
        long losses,
        long breakeven,
        BigDecimal winRate,
        BigDecimal totalNetPnl,
        long provisionalTrades,
        BigDecimal totalFees,
        BigDecimal totalFunding) {}
