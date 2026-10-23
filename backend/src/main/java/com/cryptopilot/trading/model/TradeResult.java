package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The amounts of a closed trade, at the stored scale of 8 decimals.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97; TECHNICAL_DESIGN 7.8.
 *
 * @param realizedPnl the price result only, without fees or funding
 * @param entryFee the fee of the entry fill
 * @param exitFee the fee of the exit fill; for a liquidation, the clearance fee
 * @param fundingFeeAmount the signed funding of a Futures trade, received positive and paid negative; {@code null}
 *     for Spot
 */
public record TradeResult(
        BigDecimal realizedPnl, BigDecimal entryFee, BigDecimal exitFee, BigDecimal fundingFeeAmount) {

    public TradeResult {
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(exitFee, "exitFee");
    }

    /** Entry fee plus exit fee, the stored {@code fee_amount}. */
    public BigDecimal feeAmount() {
        return entryFee.add(exitFee);
    }

    /** The stored amounts of the trade. */
    public TradeAmounts amounts() {
        return new TradeAmounts(realizedPnl, feeAmount(), fundingFeeAmount);
    }

    /** The net profit and loss, by {@link TradeAmounts#netPnl}. */
    public BigDecimal netPnl() {
        return amounts().netPnl();
    }
}
