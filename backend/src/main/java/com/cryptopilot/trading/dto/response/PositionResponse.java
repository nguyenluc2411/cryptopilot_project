package com.cryptopilot.trading.dto.response;

import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The position an executed plan opened (UC-20, SCR-18), from its journal record.
 *
 * <p>Rule: BR-34, BR-35, BR-36; UC-20; Q-55; D-78, D-119.
 *
 * @param status OPEN or CLOSED
 * @param fillPrice the actual fill price of the entry
 * @param quantity the quantity held
 * @param entryTime when the entry filled: the open of the candle that reached it, or the activation instant (D-78)
 * @param entryFee the fee paid on the entry fill, by its liquidity role
 * @param liquidationPrice Futures only: the estimated liquidation price of the plan's snapshot; {@code null} on Spot
 * @param currentPrice OPEN only: the price the unrealized P/L is read at, the Futures mark price or the Spot last
 *     price; {@code null} when the position is closed or no current price is cached
 * @param unrealizedPnl OPEN only: {@code (currentPrice − fillPrice) × quantity} for LONG, mirrored for SHORT, without
 *     fees or funding (Q-55); {@code null} with {@code currentPrice}
 */
public record PositionResponse(
        PositionStatus status,
        BigDecimal fillPrice,
        BigDecimal quantity,
        Instant entryTime,
        BigDecimal entryFee,
        BigDecimal liquidationPrice,
        BigDecimal currentPrice,
        BigDecimal unrealizedPnl) {}
