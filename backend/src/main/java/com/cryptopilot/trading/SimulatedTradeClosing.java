package com.cryptopilot.trading;

import com.cryptopilot.trading.model.enums.CloseReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * How an OPEN simulated position closed, as the journal stores it. Every value is computed by the {@code trading}
 * module; the journal stores them and decides nothing.
 *
 * <p>Rule: BR-35, BR-36, BR-37; D-97 and its note of 2026-10-24; D-119.
 *
 * @param positionId the journal record
 * @param version the row's version the position was tracked at; the close only applies to that version
 * @param exitPrice the exit fill price: after slippage for a stop loss, the liquidation price for a liquidation
 * @param exitTime when the exit counts: the open time of the candle that decided it (D-78)
 * @param reason why the position closed
 * @param realizedPnl the price result only (BR-35)
 * @param feeAmount the stored entry fee plus the exit fee (BR-36)
 * @param fundingFeeAmount the signed funding known so far; {@code null} for Spot (BR-37)
 * @param fundingIncomplete whether a settlement of the funding may be missing; always false for Spot
 * @param missingFundingTimes the settlement instants known to be missing, oldest first
 */
public record SimulatedTradeClosing(
        UUID positionId,
        long version,
        BigDecimal exitPrice,
        Instant exitTime,
        CloseReason reason,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal fundingFeeAmount,
        boolean fundingIncomplete,
        List<Instant> missingFundingTimes) {

    public SimulatedTradeClosing {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(exitPrice, "exitPrice");
        Objects.requireNonNull(exitTime, "exitTime");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(feeAmount, "feeAmount");
        missingFundingTimes = List.copyOf(Objects.requireNonNull(missingFundingTimes, "missingFundingTimes"));
        if (fundingFeeAmount == null && fundingIncomplete) {
            throw new IllegalArgumentException("a trade without funding cannot have an incomplete funding");
        }
    }
}
