package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The amounts of a closed trade, at the stored scale of 8 decimals. Funding is not part of it (T-045).
 *
 * <p>Rule: BR-35, BR-36, BR-38; D-97; TECHNICAL_DESIGN 7.8.
 *
 * @param realizedPnl the price result only, without fees or funding
 * @param entryFee the fee of the entry fill
 * @param exitFee the fee of the exit fill; for a liquidation, the clearance fee
 */
public record TradeResult(BigDecimal realizedPnl, BigDecimal entryFee, BigDecimal exitFee) {

    public TradeResult {
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(exitFee, "exitFee");
    }

    /** Entry fee plus exit fee, the stored {@code fee_amount}. */
    public BigDecimal feeAmount() {
        return entryFee.add(exitFee);
    }

    /** Realized profit and loss minus the fees; funding is added by T-045. */
    public BigDecimal netPnl() {
        return realizedPnl.subtract(feeAmount());
    }
}
