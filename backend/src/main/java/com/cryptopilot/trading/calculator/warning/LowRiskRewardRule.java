package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * WARNING LOW_RR when the plan's reward to risk ratio is strictly below the configured minimum; a ratio equal to it
 * passes.
 *
 * <p>Rule: BR-24, BR-29; TECHNICAL_DESIGN 7.5.
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (R-multiples:
 * the reward of a trade in units of its initial risk).
 */
public final class LowRiskRewardRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        BigDecimal ratio = plan.sized().riskRewardRatio();
        BigDecimal minimum = plan.thresholds().minRiskRewardRatio();
        if (ratio.compareTo(minimum) >= 0) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.LOW_RR,
                "the reward to risk ratio " + WarningText.show(ratio) + " is below " + WarningText.show(minimum)));
    }
}
