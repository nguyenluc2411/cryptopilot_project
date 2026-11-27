package com.cryptopilot.paper.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A fill changed a paper Futures position: the position as it stands after the fill. Published inside the fill's
 * transaction, for the journal to keep one record per position over its life (RF-35): opened by its first fill,
 * closed with the position's own exit figures once it closes.
 *
 * <p>Rule: BM04 F-06; D-117; RF-33, RF-35.
 *
 * @param userId the Trader
 * @param positionId the position
 * @param pairId its pair
 * @param orderId the order of the fill
 * @param orderPlanId the plan that order carries out, or {@code null}
 * @param orderType the order's type: TAKE_PROFIT_MARKET and STOP_MARKET name a take profit or stop loss close
 * @param maker whether the fill rested in the book
 * @param price the fill price
 * @param opened whether the fill opened or added to the position (else it reduced or closed it)
 * @param longPosition whether the position is long
 * @param leverage its leverage
 * @param entryPrice its average entry price
 * @param maxQuantity the largest quantity it reached
 * @param open whether it is still open
 * @param closeAvgPrice the average price it was closed at, or {@code null} while nothing closed
 * @param closedQuantity the quantity closed so far
 * @param realizedPnl what it realised so far, fees and funding apart
 * @param feeAmount the commission and liquidation fee it paid so far
 * @param fundingAmount the funding it received (positive) or paid (negative) so far
 * @param closeReason MANUAL, LIQUIDATION or SYSTEM once closed, else {@code null}
 * @param openedAt when it opened
 * @param at when the fill executed
 */
public record PaperFuturesPositionFilled(
        UUID userId,
        UUID positionId,
        UUID pairId,
        UUID orderId,
        UUID orderPlanId,
        String orderType,
        boolean maker,
        BigDecimal price,
        boolean opened,
        boolean longPosition,
        int leverage,
        BigDecimal entryPrice,
        BigDecimal maxQuantity,
        boolean open,
        BigDecimal closeAvgPrice,
        BigDecimal closedQuantity,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal fundingAmount,
        String closeReason,
        Instant openedAt,
        Instant at) {

    public PaperFuturesPositionFilled {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(maxQuantity, "maxQuantity");
        Objects.requireNonNull(at, "at");
    }
}
