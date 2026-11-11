package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One fill of a paper Futures order, with the exchange's field names. Binance: an item of
 * {@code GET /fapi/v1/userTrades}. A fill that reversed a one-way position is two items, one per position.
 *
 * @param id the fill
 * @param orderId its order
 * @param symbol the pair
 * @param side BUY or SELL
 * @param positionSide BOTH in one-way mode
 * @param price the price it executed at
 * @param qty the quantity
 * @param quoteQty its value in USDT
 * @param realizedPnl what it realised, zero when it opened or increased the position
 * @param commission the fee, zero until fees are configured
 * @param commissionAsset USDT
 * @param maker whether it rested in the book
 * @param buyer whether it bought
 * @param positionId the position it acted on (CryptoPilot's, so the position history can be followed)
 * @param time when it executed; for a fill a replay applied, when it was applied
 * @param sourceTime for a fill a replay applied, the time of the candle whose price it took; {@code null} for a live
 *     fill (CryptoPilot's, plan 9.5)
 */
public record FuturesTradeResponse(
        UUID id,
        UUID orderId,
        String symbol,
        OrderSide side,
        PositionSide positionSide,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal quoteQty,
        BigDecimal realizedPnl,
        BigDecimal commission,
        String commissionAsset,
        boolean maker,
        boolean buyer,
        UUID positionId,
        Instant time,
        Instant sourceTime) {}
