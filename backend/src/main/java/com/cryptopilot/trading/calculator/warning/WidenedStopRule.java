package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskProfileLimits;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The warnings of a stop loss of an open position moved further from its entry, against the Trader's risk profile, as
 * the sizing compares a new plan: OVERSIZED_POSITION when the position's risk at the new stop is a larger share of the
 * capital than the risk per trade, TOTAL_OPEN_RISK when the open risk with the new stop is above the maximum total open
 * risk. Both are WARNING, never blocking: the change is stored (whether it should be refused is Q-79).
 *
 * <p>Rule: BR-29, BR-66; D-53, D-121, D-124; Q-79.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (the open risk
 * of all positions as a share of equity; a stop moved away raises it).
 */
public final class WidenedStopRule {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private WidenedStopRule() {}

    /**
     * The warnings of a widened stop loss; empty when both shares are within the profile's limits.
     *
     * <p>Rule: BR-29; D-124.
     *
     * @param positionRisk the position's risk at the new stop loss
     * @param totalOpenRisk the risk of the Trader's ACTIVE plans and open positions, this one at its new stop loss
     * @param capital the profile's default capital, above 0
     * @param limits the limits of the Trader's risk profile
     */
    public static List<PlanWarning> warnings(
            BigDecimal positionRisk, BigDecimal totalOpenRisk, BigDecimal capital, RiskProfileLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (Objects.requireNonNull(capital, "capital").signum() <= 0) {
            throw new IllegalArgumentException("the capital must be above 0, was " + capital);
        }
        BigDecimal riskPercent = Rounding.divide(positionRisk.multiply(HUNDRED), capital);
        BigDecimal totalPercent = Rounding.divide(totalOpenRisk.multiply(HUNDRED), capital);
        List<PlanWarning> warnings = new ArrayList<>();
        if (riskPercent.compareTo(limits.riskPerTradePercent()) > 0) {
            warnings.add(new PlanWarning(
                    WarningType.OVERSIZED_POSITION,
                    "the position's risk at the new stop loss, " + WarningText.show(riskPercent)
                            + "% of capital, is above the risk profile's "
                            + WarningText.show(limits.riskPerTradePercent()) + "% per trade"));
        }
        if (totalPercent.compareTo(limits.maxTotalOpenRiskPercent()) > 0) {
            warnings.add(new PlanWarning(
                    WarningType.TOTAL_OPEN_RISK,
                    "the total open risk with the new stop loss, " + WarningText.show(totalPercent)
                            + "% of capital, is above the risk profile's maximum of "
                            + WarningText.show(limits.maxTotalOpenRiskPercent()) + "%"));
        }
        return warnings;
    }
}
