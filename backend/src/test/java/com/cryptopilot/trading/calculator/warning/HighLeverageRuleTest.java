package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.WarningSeverity;
import com.cryptopilot.trading.WarningType;
import org.junit.jupiter.api.Test;

/**
 * HIGH_LEVERAGE around the maximum leverage of the Trader's risk profile (5x for BALANCED), never the old fixed 20x
 * (D-53, A-34).
 *
 * <p>Rule: BR-29, BR-66.
 */
class HighLeverageRuleTest {

    private final WarningRule rule = new HighLeverageRule();

    @Test
    void BR29_leverageEqualToTheProfile_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().leverage(5).build())).isEmpty();
    }

    @Test
    void BR29_leverageOneAboveTheProfile_isAWarning() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().leverage(6).build())).hasValueSatisfying(w -> {
            assertThat(w.type()).isEqualTo(WarningType.HIGH_LEVERAGE);
            assertThat(w.severity()).isEqualTo(WarningSeverity.WARNING);
            assertThat(w.message()).contains("6x").contains("maximum of 5x");
        });
    }

    @Test
    void BR29_leverageOneBelowTheProfile_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().leverage(4).build())).isEmpty();
    }

    @Test
    void BR66_theAggressiveCeiling_isTheProfilesNotTwenty() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().profile("2", 10).leverage(10).build()))
                .isEmpty();
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().profile("2", 10).leverage(11).build()))
                .isPresent();
    }

    @Test
    void BR29_aSpotPlan_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.spot().build())).isEmpty();
    }
}
