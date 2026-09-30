package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.WarningType;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import java.util.Optional;

/**
 * WARNING HIGH_LEVERAGE when the leverage is above the maximum of the Trader's risk profile. The comparison is the
 * sizing's (T-035); the threshold is the profile's, not a system setting. A Spot plan has leverage 1 and never raises
 * it.
 *
 * <p>Rule: BR-29, BR-66; TECHNICAL_DESIGN 7.5; D-53; A-34.
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
 * (leverage and margin).
 */
public final class HighLeverageRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        if (!plan.sized().leverageAboveProfile()) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.HIGH_LEVERAGE,
                "leverage " + plan.plan().leverage() + "x is above the risk profile's maximum of "
                        + plan.plan().profile().maxLeverage() + "x"));
    }
}
