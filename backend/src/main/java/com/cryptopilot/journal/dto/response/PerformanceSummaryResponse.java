package com.cryptopilot.journal.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The metrics of SCR-23 and of the widget of SCR-08. {@code trades = 0} is the empty result that shows MSG41.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-38, BR-42, BR-62.
 *
 * @param from the first day counted; {@code null} when counted from the first trade
 * @param to the first day no longer counted
 * @param trades the closed trades counted
 * @param wins trades with a net P/L above 0
 * @param losses trades with a net P/L below 0
 * @param breakeven trades with a net P/L of exactly 0
 * @param winRate {@code wins / (wins + losses)} from 0 to 1; {@code null} without a win or a loss
 * @param totalNetPnl the sum of the net P/L
 * @param provisionalTrades trades whose funding may still change their net P/L
 * @param totalFees the sum of the fees
 * @param totalFunding the sum of the signed funding, + received, − paid
 * @param advanced the metrics of the full dashboard; {@code null} on a plan without {@code ADVANCED_PERFORMANCE}
 */
public record PerformanceSummaryResponse(
        LocalDate from,
        LocalDate to,
        long trades,
        long wins,
        long losses,
        long breakeven,
        BigDecimal winRate,
        BigDecimal totalNetPnl,
        long provisionalTrades,
        BigDecimal totalFees,
        BigDecimal totalFunding,
        PerformanceAdvancedResponse advanced) {}
