package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The amounts of a closed trade, at the stored scale of 8 decimals. A Futures trade can close while its funding is
 * incomplete: it then carries the funding known so far, the flag and the missing instants, and its net profit and
 * loss is provisional.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97 and its note of 2026-10-24; A-05; TECHNICAL_DESIGN 7.8.
 *
 * @param realizedPnl the price result only, without fees or funding
 * @param entryFee the fee of the entry fill
 * @param exitFee the fee of the exit fill; for a liquidation, the clearance fee
 * @param funding the funding of a Futures trade, complete or not; {@code null} for Spot
 */
public record TradeResult(BigDecimal realizedPnl, BigDecimal entryFee, BigDecimal exitFee, FundingFee funding) {

    public TradeResult {
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(exitFee, "exitFee");
    }

    /** Entry fee plus exit fee, the stored {@code fee_amount}. */
    public BigDecimal feeAmount() {
        return entryFee.add(exitFee);
    }

    /** The signed funding known so far, the stored {@code funding_fee_amount}; {@code null} for Spot. */
    public BigDecimal fundingFeeAmount() {
        return funding == null ? null : funding.amount();
    }

    /** Whether a settlement of the funding may be missing, so the funding and the net are provisional. */
    public boolean fundingIncomplete() {
        return funding != null && funding.incomplete();
    }

    /** The settlement instants known to be missing, oldest first; empty for Spot and for a complete funding. */
    public List<Instant> missingFundingTimes() {
        return funding == null ? List.of() : funding.missingFundingTimes();
    }

    /** The stored amounts of the trade. */
    public TradeAmounts amounts() {
        return new TradeAmounts(realizedPnl, feeAmount(), fundingFeeAmount(), fundingIncomplete());
    }

    /** The net profit and loss by {@link TradeAmounts#netPnl}, provisional while the funding is incomplete. */
    public NetPnl netPnl() {
        return amounts().netPnl();
    }
}
