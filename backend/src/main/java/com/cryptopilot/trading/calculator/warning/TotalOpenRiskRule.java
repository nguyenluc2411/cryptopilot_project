package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningType;
import java.util.Optional;

/**
 * WARNING TOTAL_OPEN_RISK when the plan's risk plus the risk of the Trader's other ACTIVE plans and open positions is
 * above the maximum total open risk of the Trader's risk profile, in percent of capital. The total and the comparison
 * are the sizing's (T-035); a total equal to the maximum passes.
 *
 * <p>Rule: BR-29, BR-66; TECHNICAL_DESIGN 7.5; D-53; A-34; Q-27.
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (position
 * sizing: the total risk of all open positions as a share of equity, the portfolio heat).
 */
public final class TotalOpenRiskRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        if (!plan.sized().totalOpenRiskAboveProfile()) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.TOTAL_OPEN_RISK,
                "the total open risk of " + WarningText.show(plan.sized().totalOpenRiskPercent())
                        + "% of capital is above the risk profile's maximum of "
                        + WarningText.show(plan.plan().profile().maxTotalOpenRiskPercent()) + "%"));
    }
}
