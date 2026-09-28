package com.cryptopilot.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.user.RiskProfile;
import com.cryptopilot.user.RiskProfileParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The shipped risk profiles are the BR-66 v1 table, and a context with an invalid profile does not start.
 *
 * <p>Rule: BR-66, BR-30; D-53 (rule 6).
 */
class RiskProfilePropertiesTest {

    private static final String PREFIX = "cryptopilot.user.risk-profiles.profiles.";

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RiskProfileConfig.class);

    private final ApplicationContextRunner bare =
            new ApplicationContextRunner().withUserConfiguration(RiskProfileConfig.class);

    @ParameterizedTest(name = "{0}: {1} %, {2}x, {3} %, {4}")
    @CsvSource({"CONSERVATIVE, 0.5, 3,  2, 75", "BALANCED,     1,   5,  4, 65", "AGGRESSIVE,   2,   10, 6, 55"})
    void BR66_theShippedProfiles_areTheV1Table(
            RiskProfile profile, String riskPerTrade, int leverage, String openRisk, int minScore) {
        shipped.run(context -> {
            RiskProfileParameters parameters =
                    context.getBean(RiskProfileProperties.class).parameters(profile);

            assertThat(parameters.profile()).isEqualTo(profile);
            assertThat(parameters.riskPerTradePercent()).isEqualByComparingTo(riskPerTrade);
            assertThat(parameters.maxFuturesLeverage()).isEqualTo(leverage);
            assertThat(parameters.maxTotalOpenRiskPercent()).isEqualByComparingTo(openRisk);
            assertThat(parameters.minSetupScoreToAlert()).isEqualTo(minScore);
        });
    }

    /** No profile may exceed AGGRESSIVE on any value (BR-66). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "BALANCED.risk-per-trade-percent=2.5",
                "BALANCED.max-futures-leverage=11",
                "CONSERVATIVE.max-total-open-risk-percent=7"
            })
    void BR66_aProfileAboveAggressive_stopsTheContext(String override) {
        shipped.withPropertyValues(PREFIX + override).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("exceeds the AGGRESSIVE values"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "BALANCED.risk-per-trade-percent=0.09",
                "AGGRESSIVE.risk-per-trade-percent=10.5",
                "BALANCED.max-futures-leverage=0",
                "BALANCED.max-total-open-risk-percent=0.5",
                "BALANCED.min-setup-score-to-alert=101",
                "BALANCED.min-setup-score-to-alert=-1"
            })
    void BR66_anOutOfRangeValue_stopsTheContext(String override) {
        shipped.withPropertyValues(PREFIX + override)
                .run(context -> assertThat(context).hasFailed());
    }

    /** The BR-30 bounds of a plan's risk % are accepted as a profile's risk per trade. */
    @Test
    void BR30_theBoundsOfARiskPercent_areAccepted() {
        shipped.withPropertyValues(
                        PREFIX + "CONSERVATIVE.risk-per-trade-percent=0.1",
                        PREFIX + "AGGRESSIVE.risk-per-trade-percent=10",
                        PREFIX + "AGGRESSIVE.max-total-open-risk-percent=10")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void BR66_aMissingProfile_stopsTheContext() {
        bare.withPropertyValues(
                        PREFIX + "BALANCED.risk-per-trade-percent=1",
                        PREFIX + "BALANCED.max-futures-leverage=5",
                        PREFIX + "BALANCED.max-total-open-risk-percent=4",
                        PREFIX + "BALANCED.min-setup-score-to-alert=65")
                .run(context -> assertThat(context).hasFailed());
        bare.run(context -> assertThat(context).hasFailed());
    }
}
