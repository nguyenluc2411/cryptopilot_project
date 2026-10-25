package com.cryptopilot.trading;

import com.cryptopilot.trading.model.TradeAmounts;
import java.math.BigDecimal;

/**
 * The net profit and loss of a closed trade for the modules outside {@code trading}: the one formula of
 * {@link TradeAmounts#netPnl(BigDecimal, BigDecimal, BigDecimal)}, so the journal list and the plan detail show the
 * same amount for the same trade.
 *
 * <p>Rule: BR-38; D-97 and its note of 2026-10-24.
 */
public final class TradeNetPnl {

    private TradeNetPnl() {}

    /**
     * {@code realizedPnl − feeAmount + fundingFeeAmount}; the funding is left out when {@code null} (Spot).
     *
     * @throws NullPointerException when {@code realizedPnl} or {@code feeAmount} is null
     */
    public static BigDecimal of(BigDecimal realizedPnl, BigDecimal feeAmount, BigDecimal fundingFeeAmount) {
        return TradeAmounts.netPnl(realizedPnl, feeAmount, fundingFeeAmount);
    }
}
