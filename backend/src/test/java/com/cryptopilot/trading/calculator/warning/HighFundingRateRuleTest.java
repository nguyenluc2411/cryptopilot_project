package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.WarningSeverity;
import com.cryptopilot.trading.WarningType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * HIGH_FUNDING_RATE around the seeded 0.001, raised only for the side that pays: a LONG pays a positive rate, a SHORT
 * a negative one (BR-37).
 *
 * <p>Rule: BR-29, BR-37.
 */
class HighFundingRateRuleTest {

    private final WarningRule rule = new HighFundingRateRule();

    @ParameterizedTest(name = "LONG, rate {0} → warning {1}")
    @CsvSource({"0.00099, false", "0.001, true", "0.00101, true", "-0.002, false"})
    void BR29_long_paysAPositiveRateAtOrAboveTheThreshold(String rate, boolean raised) {
        assertThat(rule.evaluate(PlanFixture.futuresLong().fundingRate(rate).build())
                        .isPresent())
                .isEqualTo(raised);
    }

    @ParameterizedTest(name = "SHORT, rate {0} → warning {1}")
    @CsvSource({"-0.00099, false", "-0.001, true", "-0.00101, true", "0.002, false"})
    void BR29_short_paysANegativeRateAtOrAboveTheThreshold(String rate, boolean raised) {
        assertThat(rule.evaluate(PlanFixture.futuresShort().fundingRate(rate).build())
                        .isPresent())
                .isEqualTo(raised);
    }

    @Test
    void BR29_theWarning_hasItsTypeSeverityAndRate() {
        assertThat(rule.evaluate(PlanFixture.futuresLong().fundingRate("0.001").build()))
                .hasValueSatisfying(w -> {
                    assertThat(w.type()).isEqualTo(WarningType.HIGH_FUNDING_RATE);
                    assertThat(w.severity()).isEqualTo(WarningSeverity.WARNING);
                    assertThat(w.message()).contains("0.001").contains("LONG");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void BR29_withoutAKnownRate_raisesNothing(boolean futures) {
        PlanFixture plan = futures ? PlanFixture.futuresLong() : PlanFixture.spot();

        assertThat(rule.evaluate(plan.build())).isEmpty();
    }
}
