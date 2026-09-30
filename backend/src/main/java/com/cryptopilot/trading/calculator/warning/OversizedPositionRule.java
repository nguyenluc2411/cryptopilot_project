package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningType;
import java.util.Optional;

/**
 * WARNING OVERSIZED_POSITION when the plan risks a larger share of its capital than the risk per trade of the
 * Trader's risk profile. The comparison is the sizing's (T-035); the threshold is the profile's, not a system
 * setting.
 *
 * <p>Rule: BR-29, BR-66; TECHNICAL_DESIGN 7.5; D-53; A-34.
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (position
 * sizing: the risk per trade as a fixed share of equity).
 */
public final class OversizedPositionRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        if (!plan.sized().riskPercentAboveProfile()) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.OVERSIZED_POSITION,
                "the risk of " + WarningText.show(plan.plan().riskPercent())
                        + "% of capital is above the risk profile's "
                        + WarningText.show(plan.plan().profile().riskPerTradePercent()) + "% per trade"));
    }
}
