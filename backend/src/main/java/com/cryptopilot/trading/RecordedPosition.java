package com.cryptopilot.trading;

import com.cryptopilot.trading.model.TradeAmounts;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The position of an executed plan as the journal holds it, for the plan detail (UC-20, SCR-18). The exit values are
 * {@code null} while it is OPEN.
 *
 * <p>Rule: BR-34, BR-35, BR-36, BR-37, BR-38; D-97 and its note of 2026-10-24; D-119.
 *
 * @param planId the plan
 * @param status OPEN or CLOSED
 * @param entryPrice the actual fill price
 * @param quantity the quantity held
 * @param entryTime when the fill counts (D-78)
 * @param entryFee the fee of the entry fill
 * @param liquidationPrice the liquidation price of a Futures position at its fill, or {@code null}
 * @param exitPrice the exit fill price, once CLOSED
 * @param exitTime when the exit counts, once CLOSED
 * @param closeReason why it closed, once CLOSED
 * @param realizedPnl the price result only, once CLOSED
 * @param feeAmount the entry fee plus the exit fee, once CLOSED
 * @param fundingFeeAmount the signed funding known so far of a closed Futures position; {@code null} for Spot
 * @param fundingIncomplete whether a settlement of that funding may be missing, so the net is provisional
 */
public record RecordedPosition(
        UUID planId,
        PositionStatus status,
        BigDecimal entryPrice,
        BigDecimal quantity,
        Instant entryTime,
        BigDecimal entryFee,
        BigDecimal liquidationPrice,
        BigDecimal exitPrice,
        Instant exitTime,
        CloseReason closeReason,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal fundingFeeAmount,
        boolean fundingIncomplete) {

    public RecordedPosition {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(entryTime, "entryTime");
        Objects.requireNonNull(entryFee, "entryFee");
        if (status == PositionStatus.CLOSED && (realizedPnl == null || feeAmount == null)) {
            throw new IllegalArgumentException("a closed position has its realized P/L and its fees");
        }
    }

    /** An OPEN position: nothing of the exit yet. */
    public static RecordedPosition open(
            UUID planId,
            BigDecimal entryPrice,
            BigDecimal quantity,
            Instant entryTime,
            BigDecimal entryFee,
            BigDecimal liquidationPrice) {
        return new RecordedPosition(
                planId,
                PositionStatus.OPEN,
                entryPrice,
                quantity,
                entryTime,
                entryFee,
                liquidationPrice,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

    /**
     * The stored amounts of a CLOSED position, whose {@link TradeAmounts#netPnl()} is its net profit and loss,
     * provisional while the funding is incomplete; {@code null} while OPEN.
     *
     * <p>Rule: BR-38; D-97 rule 4.
     */
    public TradeAmounts amounts() {
        return status != PositionStatus.CLOSED
                ? null
                : new TradeAmounts(realizedPnl, feeAmount, fundingFeeAmount, fundingIncomplete);
    }
}
