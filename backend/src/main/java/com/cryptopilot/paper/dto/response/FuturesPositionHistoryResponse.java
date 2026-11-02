package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionCloseReason;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One closed paper Futures position, from its first fill until its size was back to zero. The exchange shows this
 * on its app only (Position History); a one-way reversal is two positions.
 *
 * @param positionId the position
 * @param symbol the pair
 * @param positionSide BOTH in one-way mode
 * @param direction LONG or SHORT
 * @param marginType CROSSED or ISOLATED
 * @param leverage the leverage it closed with
 * @param maxQuantity the largest size it reached
 * @param closedQuantity what was closed in all
 * @param entryPrice the average entry price
 * @param avgClosePrice the average price it was closed at
 * @param realizedPnl what its closes realised
 * @param fundingFee the funding it received (positive) or paid (negative)
 * @param liquidationFee the clearance fee of its liquidation, zero otherwise
 * @param netProfit realised plus funding less the clearance fee
 * @param closeReason MANUAL, LIQUIDATION, …
 * @param openTime when its first fill was
 * @param closeTime when it was closed
 */
public record FuturesPositionHistoryResponse(
        UUID positionId,
        String symbol,
        PositionSide positionSide,
        PositionDirection direction,
        MarginType marginType,
        int leverage,
        BigDecimal maxQuantity,
        BigDecimal closedQuantity,
        BigDecimal entryPrice,
        BigDecimal avgClosePrice,
        BigDecimal realizedPnl,
        BigDecimal fundingFee,
        BigDecimal liquidationFee,
        BigDecimal netProfit,
        PositionCloseReason closeReason,
        Instant openTime,
        Instant closeTime) {}
