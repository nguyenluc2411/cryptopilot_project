package com.cryptopilot.trading.dto.response;

import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The position an executed plan opened (UC-20, SCR-18), from its journal record. The exit, its amounts and the net
 * P/L are set once it is CLOSED.
 *
 * <p>Rule: BR-34, BR-35, BR-36, BR-37, BR-38, BR-42; UC-20, UC-21; Q-55; D-78, D-97 and its note of 2026-10-24,
 * D-119, D-124.
 *
 * @param status OPEN or CLOSED
 * @param fillPrice the actual fill price of the entry
 * @param quantity the quantity held
 * @param entryTime when the entry filled: the open of the candle that reached it, or the activation instant (D-78)
 * @param entryFee the fee paid on the entry fill, by its liquidity role
 * @param liquidationPrice Futures only: the liquidation price at the fill price, which the mark price is compared
 *     with; {@code null} on Spot and for a position no price liquidates
 * @param currentPrice OPEN only: the price the unrealized P/L is read at, the Futures mark price or the Spot last
 *     price; {@code null} when the position is closed or no current price is cached
 * @param unrealizedPnl OPEN only: {@code (currentPrice − fillPrice) × quantity} for LONG, mirrored for SHORT, without
 *     fees or funding (Q-55); {@code null} with {@code currentPrice}
 * @param exitPrice CLOSED only: the exit fill price, after the slippage of a stop loss; the liquidation price of a
 *     liquidation
 * @param exitTime CLOSED only: when the exit counts, the open time of the candle that decided it (D-78)
 * @param closeReason CLOSED only: TAKE_PROFIT, STOP_LOSS or LIQUIDATION
 * @param realizedPnl CLOSED only: the price result, without fees or funding (BR-35)
 * @param feeAmount CLOSED only: the entry fee plus the exit fee, or the clearance fee of a liquidation (BR-36)
 * @param fundingFeeAmount CLOSED Futures only: the signed funding known so far, received positive (BR-37);
 *     {@code null} on Spot
 * @param netPnl CLOSED only: {@code realizedPnl − feeAmount + fundingFeeAmount} (BR-38)
 * @param provisional CLOSED only: whether a funding settlement may still be missing, so {@code fundingFeeAmount} and
 *     {@code netPnl} can still change; {@code false} while OPEN
 * @param stopLoss the current stop loss, the one the exits read; the last one once CLOSED (D-124)
 * @param takeProfit the current take profit; the last one once CLOSED
 * @param originalStopLoss the stop loss at the fill, never changed
 * @param originalTakeProfit the take profit at the fill, never changed
 * @param stopWidened whether a stop loss was ever moved further from the entry than the original one
 * @param levelChangeCount how many changes of the levels were made
 * @param levelChanges every change, oldest first; empty when none
 * @param breakevenPrice OPEN only: the stop price on the tick at which a stop-out nets zero after the entry fee, the
 *     taker exit fee with slippage and the funding so far; {@code null} once CLOSED or when it cannot be computed
 * @param breakevenProvisional whether a funding settlement may be missing from {@code breakevenPrice}
 * @param levelWarnings the non-blocking warnings of the change just made, in its response only; empty on every read
 * @param levelsEffectiveFrom the open time of the first 1-minute candle the current levels act on, after the last
 *     change; {@code null} when the levels were never changed (D-124, Q-80)
 */
public record PositionResponse(
        PositionStatus status,
        BigDecimal fillPrice,
        BigDecimal quantity,
        Instant entryTime,
        BigDecimal entryFee,
        BigDecimal liquidationPrice,
        BigDecimal currentPrice,
        BigDecimal unrealizedPnl,
        BigDecimal exitPrice,
        Instant exitTime,
        CloseReason closeReason,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal fundingFeeAmount,
        BigDecimal netPnl,
        boolean provisional,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal originalStopLoss,
        BigDecimal originalTakeProfit,
        boolean stopWidened,
        int levelChangeCount,
        List<PositionLevelChangeResponse> levelChanges,
        BigDecimal breakevenPrice,
        boolean breakevenProvisional,
        List<PlanWarningResponse> levelWarnings,
        Instant levelsEffectiveFrom) {}
