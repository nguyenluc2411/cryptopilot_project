package com.cryptopilot.trading.model;

import com.cryptopilot.market.MarketType;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Everything the warning rules read: the plan as entered, its sizing (T-035), on Futures its margin and liquidation
 * estimate (T-036) and the pair's current funding rate, and the configured thresholds.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29; TECHNICAL_DESIGN 7.5.
 *
 * @param plan the plan's values and the Trader's risk profile
 * @param sized the sizing of the plan
 * @param liquidation the liquidation estimate on Futures; {@code null} on Spot
 * @param fundingRate the pair's current funding rate on Futures, {@code null} on Spot or when none is known
 * @param thresholds the BR-29 thresholds from system settings
 */
public record PlanCalculation(
        RiskInput plan,
        RiskCalculation sized,
        LiquidationEstimate liquidation,
        BigDecimal fundingRate,
        WarningThresholds thresholds) {

    public PlanCalculation {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(sized, "sized");
        Objects.requireNonNull(thresholds, "thresholds");
        boolean futures = plan.market() == MarketType.FUTURES;
        if (futures != (liquidation != null)) {
            throw new IllegalArgumentException("a Futures plan has a liquidation estimate and a Spot plan has none");
        }
        if (!futures && fundingRate != null) {
            throw new IllegalArgumentException("a Spot plan has no funding rate");
        }
    }

    /** Whether the plan is on Futures. */
    public boolean isFutures() {
        return liquidation != null;
    }
}
