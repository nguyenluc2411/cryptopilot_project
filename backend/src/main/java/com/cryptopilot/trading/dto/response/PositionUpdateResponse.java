package com.cryptopilot.trading.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A change of the position of one of the Trader's plans, pushed on {@code /user/queue/positions}: a position closed
 * by its take profit or stop loss, or liquidated. The plan detail ({@code GET /plans/{id}}) answers the same values in
 * its position block.
 *
 * <p>Rule: BR-35, BR-36, BR-38; UC-20; TECHNICAL_DESIGN 9; Q-57.
 *
 * @param positionId the journal record
 * @param planId the plan the position came from
 * @param market SPOT or FUTURES
 * @param symbol the pair's symbol, or {@code null} when it could not be read
 * @param status CLOSED
 * @param closeReason TAKE_PROFIT, STOP_LOSS or LIQUIDATION
 * @param exitPrice the exit fill price
 * @param exitTime when the exit counts (D-78)
 * @param realizedPnl the price result only
 * @param feeAmount the entry fee plus the exit fee
 * @param netPnl realized minus fees plus the funding known so far
 * @param provisional whether a funding settlement may still be missing, so {@code netPnl} can still change
 */
public record PositionUpdateResponse(
        UUID positionId,
        UUID planId,
        MarketType market,
        String symbol,
        PositionStatus status,
        CloseReason closeReason,
        BigDecimal exitPrice,
        Instant exitTime,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal netPnl,
        boolean provisional) {}
