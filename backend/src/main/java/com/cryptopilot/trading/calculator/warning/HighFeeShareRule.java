package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * WARNING HIGH_FEE_SHARE when the expected fees and slippage of a stop-loss trade are at least the configured share of
 * the plan's risk amount; a share just below it passes. Never blocking: the quantity is not re-sized for fees (BR-23).
 *
 * <p>Rule: BR-29, BR-36; D-116; TECHNICAL_DESIGN 7.5.
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (costs turn a
 * planned 1R loss into more than 1R).
 */
public final class HighFeeShareRule implements WarningRule {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        BigDecimal share = plan.costs().feeShareOfRisk();
        if (share.compareTo(plan.thresholds().feeRiskWarnRatio()) < 0) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.HIGH_FEE_SHARE,
                "fees and slippage are " + WarningText.show(share.multiply(HUNDRED)) + "% of your risk"));
    }
}
