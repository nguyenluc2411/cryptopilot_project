package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.WarningSeverity;
import com.cryptopilot.trading.WarningType;
import org.junit.jupiter.api.Test;

/**
 * WIDE_STOP_LOSS around the seeded 10 % of the entry, on both sides.
 *
 * <p>Rule: BR-29.
 */
class WideStopLossRuleTest {

    private final WarningRule rule = new WideStopLossRule();

    @Test
    void BR29_longStopExactlyTenPercentAway_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.spot().stop("90").build())).isEmpty();
    }

    @Test
    void BR29_longStopJustBeyondTenPercent_isInfo() {
        assertThat(rule.evaluate(PlanFixture.spot().stop("89.99").build())).hasValueSatisfying(w -> {
            assertThat(w.type()).isEqualTo(WarningType.WIDE_STOP_LOSS);
            assertThat(w.severity()).isEqualTo(WarningSeverity.INFO);
            assertThat(w.message()).contains("10.01%").contains("more than 10%");
        });
    }

    @Test
    void BR29_longStopJustInsideTenPercent_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.spot().stop("90.01").build())).isEmpty();
    }

    @Test
    void BR29_shortStopExactlyTenPercentAway_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresShort().stop("110").takeProfit("80").build()))
                .isEmpty();
    }

    @Test
    void BR29_shortStopJustBeyondTenPercent_isInfo() {
        assertThat(rule.evaluate(PlanFixture.futuresShort()
                        .stop("110.01")
                        .takeProfit("80")
                        .build()))
                .isPresent();
    }

    @Test
    void BR29_theThresholdIsTheConfiguredOne_notAConstant() {
        assertThat(rule.evaluate(
                        PlanFixture.spot().thresholds("1.5", "4", "0.001").build()))
                .isPresent();
    }
}
