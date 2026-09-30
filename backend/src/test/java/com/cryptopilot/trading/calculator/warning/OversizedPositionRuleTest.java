package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import org.junit.jupiter.api.Test;

/**
 * OVERSIZED_POSITION around the risk per trade of the Trader's risk profile, never a fixed setting (D-53, A-34).
 *
 * <p>Rule: BR-29, BR-66.
 */
class OversizedPositionRuleTest {

    private final WarningRule rule = new OversizedPositionRule();

    @Test
    void BR29_riskEqualToTheProfile_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().riskPercent("1").build()))
                .isEmpty();
    }

    @Test
    void BR29_riskJustAboveTheProfile_isAWarning() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().riskPercent("1.01").build()))
                .hasValueSatisfying(w -> {
                    assertThat(w.type()).isEqualTo(WarningType.OVERSIZED_POSITION);
                    assertThat(w.severity()).isEqualTo(WarningSeverity.WARNING);
                    assertThat(w.message()).contains("1.01%").contains("1% per trade");
                });
    }

    @Test
    void BR29_riskJustBelowTheProfile_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().riskPercent("0.99").build()))
                .isEmpty();
    }

    /** 2 % is within AGGRESSIVE and above CONSERVATIVE: the profile decides, the old fixed 2 % does not. */
    @Test
    void BR66_theSameRisk_followsTheProfile() {
        assertThat(rule.evaluate(PlanFixture.futuresLong()
                        .riskPercent("2")
                        .profile("2", 10)
                        .build()))
                .isEmpty();
        assertThat(rule.evaluate(PlanFixture.futuresLong()
                        .riskPercent("0.6")
                        .profile("0.5", 3)
                        .leverage(3)
                        .build()))
                .isPresent();
    }
}
