package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * INFO WIDE_STOP_LOSS when the stop loss is further from the entry than the configured percentage:
 * {@code |E − S| / E × 100 > threshold}. A distance equal to the threshold passes.
 *
 * <p>Rule: BR-29; TECHNICAL_DESIGN 7.5.
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (the stop
 * distance as the initial risk 1R).
 */
public final class WideStopLossRule implements WarningRule {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        RiskInput in = plan.plan();
        BigDecimal distancePercent =
                Rounding.divide(in.entryPrice().subtract(in.stopLoss()).abs().multiply(HUNDRED), in.entryPrice());
        BigDecimal threshold = plan.thresholds().wideStopLossPercent();
        if (distancePercent.compareTo(threshold) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.WIDE_STOP_LOSS,
                "the stop loss is " + WarningText.show(distancePercent) + "% from the entry, more than "
                        + WarningText.show(threshold) + "%"));
    }
}
