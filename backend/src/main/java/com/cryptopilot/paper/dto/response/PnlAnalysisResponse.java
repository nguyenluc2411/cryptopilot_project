package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The PnL analysis of the caller's paper account over a period (TR-28, plan section 8), of the Spot wallet, the Futures
 * wallet, or both: today's PnL, the PnL of each day and its running total, the deepest fall from a peak, and, for
 * Futures, the record of the positions closed. A day's PnL is the change of the wallet's worth over the day, less what
 * transfers brought in, so moving USDT between the wallets is neither a gain nor a loss. Amounts in USDT.
 *
 * @param market {@code SPOT}, {@code FUTURES}, or {@code ALL} for both wallets together
 * @param days the length of the period, ending today
 * @param todayPnl today's PnL so far; {@code null} while today has no snapshot to measure from
 * @param cumulativePnl the PnL of the days of the period together
 * @param maxDrawdown the deepest fall of the running total from its highest point in the period, 0 or more
 * @param maxDrawdownPercent that fall as a percent of the worth at the highest point, 2 decimals; {@code null} when the
 *     worth there is not above 0
 * @param complete whether every coin held has a current price; when not, today's figures leave the unpriced coins out
 * @param daily each day with a PnL, oldest first: a day without its snapshot, or without the next day's, is left out
 * @param trades the trades closed in the period: the Spot sale orders, the Futures positions, or both
 */
public record PnlAnalysisResponse(
        String market,
        int days,
        BigDecimal todayPnl,
        BigDecimal cumulativePnl,
        BigDecimal maxDrawdown,
        BigDecimal maxDrawdownPercent,
        boolean complete,
        List<Day> daily,
        Trades trades) {

    /**
     * One day of the period.
     *
     * @param date the day, in UTC
     * @param pnl its PnL
     * @param cumulativePnl the PnL of the period up to and including it
     */
    public record Day(LocalDate date, BigDecimal pnl, BigDecimal cumulativePnl) {}

    /**
     * The trades closed in the period, each counted once by its net result: a Futures position (realised PnL with
     * funding, less the liquidation fee and the fees of its fills) and a Spot sale order (what it netted less the share
     * of the average cost it took).
     *
     * @param closed how many closed
     * @param wins how many closed with a result above 0
     * @param losses how many closed with a result below 0
     * @param winRate wins as a percent of the closed, 2 decimals; {@code null} when none closed
     * @param totalPnl their results together
     * @param averageWin the average result of the wins; {@code null} without one
     * @param averageLoss the average result of the losses, negative; {@code null} without one
     * @param profitFactor the wins' results together over the losses' together, 2 decimals; {@code null} without a
     *     loss
     */
    public record Trades(
            int closed,
            int wins,
            int losses,
            BigDecimal winRate,
            BigDecimal totalPnl,
            BigDecimal averageWin,
            BigDecimal averageLoss,
            BigDecimal profitFactor) {}
}
