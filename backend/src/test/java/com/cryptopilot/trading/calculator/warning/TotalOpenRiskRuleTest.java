package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.Direction;
import com.cryptopilot.trading.WarningSeverity;
import com.cryptopilot.trading.WarningType;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskProfileLimits;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * TOTAL_OPEN_RISK around the maximum total open risk of the Trader's risk profile (4 % for BALANCED). The plan risks
 * exactly 10 of a 1,000 capital (1 %), so other open risk of 30 brings the total to exactly 4 %.
 *
 * <p>Rule: BR-29, BR-66; D-53; Q-27.
 */
class TotalOpenRiskRuleTest {

    private final WarningRule rule = new TotalOpenRiskRule();

    @Test
    void BR29_totalEqualToTheProfileMaximum_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().otherOpenRisk("30").build()))
                .isEmpty();
    }

    @Test
    void BR29_totalJustAboveTheProfileMaximum_isAWarning() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().otherOpenRisk("30.01").build()))
                .hasValueSatisfying(w -> {
                    assertThat(w.type()).isEqualTo(WarningType.TOTAL_OPEN_RISK);
                    assertThat(w.severity()).isEqualTo(WarningSeverity.WARNING);
                    assertThat(w.message()).contains("4.001%").contains("maximum of 4%");
                });
    }

    @Test
    void BR29_totalJustBelowTheProfileMaximum_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().otherOpenRisk("29.99").build()))
                .isEmpty();
    }

    @Test
    void BR29_withoutOtherOpenPositions_onlyThePlanCounts() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().build())).isEmpty();
        assertThat(rule.evaluate(PlanFixture.spot().riskPercent("5").build()))
                .as("5 % alone is above the 4 % maximum")
                .isPresent();
    }

    /** A Trader who never chose a profile is on CONSERVATIVE (D-64), whose maximum is 2 %. */
    @Test
    void BR66_theConservativeDefault_isComparedLikeAnyProfile() {
        PlanFixture conservative =
                PlanFixture.futuresLong().profile("0.5", 3, "2").leverage(3).riskPercent("0.5");

        assertThat(rule.evaluate(conservative.otherOpenRisk("15").build())).isEmpty();
        assertThat(rule.evaluate(conservative.otherOpenRisk("15.01").build())).isPresent();
    }

    /** No plan reaches the rule without a profile maximum, so the rule never skips the check. */
    @Test
    void BR66_aPlanWithoutAProfileMaximum_cannotBeBuilt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProfileLimits(BigDecimal.ONE, 5, BigDecimal.ZERO));
        assertThatNullPointerException().isThrownBy(() -> new RiskProfileLimits(BigDecimal.ONE, 5, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new RiskInput(
                        MarketType.SPOT,
                        Direction.LONG,
                        new BigDecimal("100"),
                        new BigDecimal("95"),
                        new BigDecimal("110"),
                        new BigDecimal("1000"),
                        BigDecimal.ONE,
                        1,
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")),
                        null,
                        BigDecimal.ZERO));
    }
}
