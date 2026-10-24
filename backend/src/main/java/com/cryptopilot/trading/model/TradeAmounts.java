package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The stored amounts of a trade and the one amount derived from them. {@link #netPnl(BigDecimal, BigDecimal,
 * BigDecimal)} is the only place the net profit and loss is computed in Java; the journal record of T-048 moves here
 * or delegates to it, and statistics use the matching SQL expression.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97; TECHNICAL_DESIGN 7.8.
 *
 * @param realizedPnl the price result only, without fees or funding
 * @param feeAmount the entry fee plus the exit fee, 0 or above
 * @param fundingFeeAmount the signed funding known so far, received positive and paid negative; {@code null} for
 *     Spot
 * @param fundingIncomplete whether a settlement of the funding may be missing; always false for Spot
 */
public record TradeAmounts(
        BigDecimal realizedPnl, BigDecimal feeAmount, BigDecimal fundingFeeAmount, boolean fundingIncomplete) {

    public TradeAmounts {
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(feeAmount, "feeAmount");
        if (fundingFeeAmount == null && fundingIncomplete) {
            throw new IllegalArgumentException("a trade without funding cannot have an incomplete funding");
        }
    }

    /** The net profit and loss of these amounts, provisional while the funding is incomplete. */
    public NetPnl netPnl() {
        return new NetPnl(netPnl(realizedPnl, feeAmount, fundingFeeAmount), fundingIncomplete);
    }

    /**
     * Net profit and loss: realized minus fees plus funding, where a Spot trade has no funding ({@code null}) and so
     * adds nothing. Derived, never stored. No rounding: the inputs are stored amounts of the same scale.
     *
     * <p>Rule: BR-38; D-97 rule 4; TECHNICAL_DESIGN 7.8.
     * <p>Reference: Binance FAQ, <i>How to Calculate Profit and Loss for Futures Contracts</i> (realized PnL excludes
     * commission and funding, which are booked separately).
     *
     * @param realizedPnl the price result (BR-35)
     * @param feeAmount the fees of the trade (BR-36)
     * @param fundingFeeAmount the signed funding (BR-37), or {@code null} for Spot
     */
    public static BigDecimal netPnl(BigDecimal realizedPnl, BigDecimal feeAmount, BigDecimal fundingFeeAmount) {
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(feeAmount, "feeAmount");
        BigDecimal net = realizedPnl.subtract(feeAmount);
        return fundingFeeAmount == null ? net : net.add(fundingFeeAmount);
    }
}
