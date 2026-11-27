package com.cryptopilot.trading;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.MarginMode;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a plan is applied with (RF-33): the paper order is placed from these values, never from what the Trader types,
 * so the order keeps the quantity the plan sized from its risk.
 *
 * <p>Rule: BR-23 to BR-27, BR-33; D-117; RF-33.
 *
 * @param planId the plan
 * @param userId its owner
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT (a Spot plan is LONG)
 * @param entryType MARKET, LIMIT or STOP
 * @param entryPrice the planned entry price
 * @param stopLoss the stop loss
 * @param takeProfit the take profit, or {@code null}
 * @param quantity the position quantity the plan sized
 * @param leverage the leverage on Futures, {@code null} on Spot
 * @param marginMode the margin mode on Futures, {@code null} on Spot
 */
public record PlanOrderTerms(
        UUID planId,
        UUID userId,
        UUID pairId,
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal quantity,
        Integer leverage,
        MarginMode marginMode) {}
