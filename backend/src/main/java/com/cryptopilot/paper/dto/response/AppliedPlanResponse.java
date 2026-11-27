package com.cryptopilot.paper.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.List;
import java.util.UUID;

/**
 * The orders a plan was applied with (RF-33).
 *
 * @param planId the plan
 * @param market SPOT or FUTURES
 * @param symbol the pair's symbol
 * @param entryOrderId the order that opens the position: once it fills, the plan is EXECUTED
 * @param orderIds every order placed, the entry first, then its take profit and stop loss
 * @param entryStatus the entry order's status: FILLED for a MARKET entry that filled at once, NEW while it waits
 * @param protectedByStopLoss whether a stop loss order was placed with it (not for a STOP entry)
 */
public record AppliedPlanResponse(
        UUID planId,
        MarketType market,
        String symbol,
        UUID entryOrderId,
        List<UUID> orderIds,
        String entryStatus,
        boolean protectedByStopLoss) {}
