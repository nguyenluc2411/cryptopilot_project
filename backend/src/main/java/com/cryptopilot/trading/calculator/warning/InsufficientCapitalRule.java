package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningType;
import java.util.Optional;

/**
 * BLOCKING INSUFFICIENT_CAPITAL when the plan needs more than its capital: on Spot the notional (BR-25), on Futures
 * the initial margin (BR-26). Both comparisons are made by the sizing and the liquidation estimate; this rule reports
 * them.
 *
 * <p>Rule: BR-25, BR-26; TECHNICAL_DESIGN 7.5.
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
 * (initial margin).
 */
public final class InsufficientCapitalRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        if (plan.isFutures()) {
            return plan.liquidation().marginExceedsCapital()
                    ? warn("the initial margin " + plan.liquidation().initialMargin(), plan)
                    : Optional.empty();
        }
        return plan.sized().exceedsCapital()
                ? warn("the notional " + plan.sized().notional(), plan)
                : Optional.empty();
    }

    private static Optional<PlanWarning> warn(String needed, PlanCalculation plan) {
        return Optional.of(new PlanWarning(
                WarningType.INSUFFICIENT_CAPITAL,
                needed + " is above the capital " + plan.plan().capital()));
    }
}
