package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * WARNING HIGH_FUNDING_RATE when the absolute funding rate is at or above the configured value and the position would
 * pay it: a LONG pays a positive rate, a SHORT a negative one. Spot plans and a Futures plan without a known rate
 * never raise it.
 *
 * <p>Rule: BR-29, BR-37; TECHNICAL_DESIGN 7.5.
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2 and 5
 * (futures prices converging to spot; the perpetual contract's funding payment plays that role).
 */
public final class HighFundingRateRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        BigDecimal rate = plan.fundingRate();
        if (rate == null || rate.abs().compareTo(plan.thresholds().highFundingRate()) < 0) {
            return Optional.empty();
        }
        int paidBy = plan.plan().direction() == Direction.LONG ? 1 : -1;
        if (rate.signum() != paidBy) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.HIGH_FUNDING_RATE,
                "the funding rate " + rate.stripTrailingZeros().toPlainString() + " is paid by a "
                        + plan.plan().direction() + " position"));
    }
}
