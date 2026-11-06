package com.cryptopilot.journal.dto.response;

import java.math.BigDecimal;

/**
 * One row of a breakdown of SCR-23.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-42, BR-62.
 *
 * @param key the pair id, the strategy id ({@code null} for the trades without a strategy) or the market
 * @param label the symbol, the strategy name ("Unassigned" for those trades) or the market
 * @param trades the closed trades of the group
 * @param wins trades with a net P/L above 0
 * @param losses trades with a net P/L below 0
 * @param breakeven trades with a net P/L of exactly 0
 * @param winRate {@code wins / (wins + losses)} from 0 to 1; {@code null} without a win or a loss
 * @param netPnl the sum of the net P/L
 * @param profitFactor gross profit / gross loss; {@code null} without losses
 * @param averageRealizedR the mean realized R; {@code null} when no trade has a stop loss
 */
public record PerformanceBreakdownResponse(
        String key,
        String label,
        long trades,
        long wins,
        long losses,
        long breakeven,
        BigDecimal winRate,
        BigDecimal netPnl,
        BigDecimal profitFactor,
        BigDecimal averageRealizedR) {}
