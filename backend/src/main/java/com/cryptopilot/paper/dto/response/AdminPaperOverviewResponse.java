package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The demo exchange at a glance for the administrator (TR-30, plan section 9): accounts, working orders, open positions,
 * the activity of the last 24 hours, what the matching engine holds, and the latest liquidations.
 *
 * @param checkedAt when the figures were read
 * @param accounts the paper accounts opened so far
 * @param accountsOpened24h those opened in the last 24 hours
 * @param workingOrders the orders still working or waiting, by market ({@code SPOT}, {@code FUTURES})
 * @param openPositions the open Futures positions
 * @param fills24h the fills of the last 24 hours
 * @param liquidations24h the positions liquidated in the last 24 hours
 * @param engineRestingOrders the orders the matching engine holds in memory now; far from the working orders, it is
 *     behind the database and catching up
 * @param latestLiquidations the latest liquidations, newest first
 */
public record AdminPaperOverviewResponse(
        Instant checkedAt,
        long accounts,
        long accountsOpened24h,
        Map<String, Long> workingOrders,
        long openPositions,
        long fills24h,
        long liquidations24h,
        int engineRestingOrders,
        List<Liquidation> latestLiquidations) {

    /**
     * One liquidation.
     *
     * @param at when the position was liquidated
     * @param symbol its pair
     * @param userId whose it was
     * @param marginType {@code ISOLATED} or {@code CROSSED}
     * @param realizedPnl what it realised over its life, funding and fees apart
     */
    public record Liquidation(Instant at, String symbol, UUID userId, String marginType, BigDecimal realizedPnl) {}
}
