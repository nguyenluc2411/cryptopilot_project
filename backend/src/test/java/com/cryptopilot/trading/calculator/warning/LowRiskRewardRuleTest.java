package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import org.junit.jupiter.api.Test;

/**
 * LOW_RR around the seeded minimum of 1.5. With E 100 and S 95 the risk is 5 per unit, so a take profit of 107.5
 * gives exactly 1.5.
 *
 * <p>Rule: BR-24, BR-29.
 */
class LowRiskRewardRuleTest {

    private final WarningRule rule = new LowRiskRewardRule();

    @Test
    void BR29_ratioEqualToTheMinimum_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().takeProfit("107.50").build()))
                .isEmpty();
    }

    @Test
    void BR29_ratioJustBelowTheMinimum_isAWarning() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().takeProfit("107.49").build()))
                .hasValueSatisfying(w -> {
                    assertThat(w.type()).isEqualTo(WarningType.LOW_RR);
                    assertThat(w.severity()).isEqualTo(WarningSeverity.WARNING);
                    assertThat(w.message()).contains("1.498").contains("below 1.5");
                });
    }

    @Test
    void BR29_ratioJustAboveTheMinimum_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().takeProfit("107.51").build()))
                .isEmpty();
    }

    @Test
    void BR29_theMinimumIsTheConfiguredOne_notAConstant() {
        assertThat(rule.evaluate(PlanFixture.futuresLong()
                        .takeProfit("107.50")
                        .thresholds("2", "10", "0.001")
                        .build()))
                .isPresent();
    }

    @Test
    void BR29_aSpotPlan_isComparedTheSameWay() {
        assertThat(rule.evaluate(PlanFixture.spot().takeProfit("107.49").build()))
                .isPresent();
    }
}
