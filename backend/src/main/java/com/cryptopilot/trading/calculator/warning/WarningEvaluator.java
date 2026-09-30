package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Applies every warning rule to a calculated plan and returns the warnings it raises, the most severe first. A plan
 * with a BLOCKING warning can be saved as a draft but not activated.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29, BR-32; TECHNICAL_DESIGN 7.5; D-53; A-34; Q-27.
 * <p>Reference: Evans, E., &amp; Fowler, M. (1997). <i>Specifications</i> (composite of independent rule objects).
 */
public final class WarningEvaluator {

    private static final List<WarningRule> RULES = List.of(
            new InsufficientCapitalRule(),
            new StopBeyondLiquidationRule(),
            new LowRiskRewardRule(),
            new OversizedPositionRule(),
            new HighLeverageRule(),
            new TotalOpenRiskRule(),
            new WideStopLossRule(),
            new HighFundingRateRule());

    private static final Comparator<PlanWarning> MOST_SEVERE_FIRST =
            Comparator.comparing(PlanWarning::severity).reversed().thenComparing(PlanWarning::type);

    private WarningEvaluator() {}

    /** The warnings the plan raises, most severe first; empty when it raises none. */
    public static List<PlanWarning> evaluate(PlanCalculation plan) {
        return RULES.stream()
                .map(rule -> rule.evaluate(plan))
                .flatMap(Optional::stream)
                .sorted(MOST_SEVERE_FIRST)
                .toList();
    }

    /** Whether any of the warnings stops the plan from being activated (MSG18). */
    public static boolean blocksActivation(List<PlanWarning> warnings) {
        return warnings.stream().anyMatch(w -> w.severity() == WarningSeverity.BLOCKING);
    }
}
