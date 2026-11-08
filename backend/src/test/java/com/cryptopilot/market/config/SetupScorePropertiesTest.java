package com.cryptopilot.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.SetupScoreTunings;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The shipped presets are the D-53 weights at version v2 with the v2 values, and a context with an invalid preset
 * or v2 value does not start.
 *
 * <p>Rule: BR-13; D-53 (rules 4 and 6).
 */
class SetupScorePropertiesTest {

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SetupScoreConfig.class);

    private static final String V2 = "cryptopilot.market.setup-score.v2.";

    private final ApplicationContextRunner bare =
            new ApplicationContextRunner().withUserConfiguration(SetupScoreConfig.class);

    @Test
    void D53_theShippedPresets_areTheD53WeightsAtVersionV2() {
        shipped.run(context -> {
            SetupScoreProperties properties = context.getBean(SetupScoreProperties.class);

            assertPreset(
                    properties.preset(SetupStyle.SCALPING),
                    "15m",
                    new ComponentWeights(20, 40, 25, 15, null),
                    new ComponentWeights(15, 35, 20, 10, 20));
            assertPreset(
                    properties.preset(SetupStyle.DAY_TRADING),
                    "1h",
                    new ComponentWeights(35, 30, 15, 20, null),
                    new ComponentWeights(30, 25, 15, 15, 15));
            assertPreset(
                    properties.preset(SetupStyle.SWING),
                    "4h",
                    new ComponentWeights(40, 20, 10, 30, null),
                    new ComponentWeights(35, 15, 10, 25, 15));
        });
    }

    @Test
    void D53_everyShippedPresetAndMarket_addsUpTo100() {
        shipped.run(context -> {
            SetupScoreProperties properties = context.getBean(SetupScoreProperties.class);
            for (SetupStyle style : SetupStyle.values()) {
                for (MarketType market : MarketType.values()) {
                    assertThat(properties.preset(style).weights(market).total())
                            .as("%s %s", style, market)
                            .isEqualTo(100);
                }
            }
        });
    }

    @Test
    void D53_aPresetWhoseWeightsDoNotAddUpTo100_stopsTheContext() {
        shipped.withPropertyValues("cryptopilot.market.setup-score.presets.SWING.spot.level=31")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void D53_aMissingPreset_stopsTheContext() {
        bare.withPropertyValues(
                        "cryptopilot.market.setup-score.presets.SWING.version=v1",
                        "cryptopilot.market.setup-score.presets.SWING.timeframe=4h",
                        "cryptopilot.market.setup-score.presets.SWING.spot.trend=40",
                        "cryptopilot.market.setup-score.presets.SWING.spot.momentum=20",
                        "cryptopilot.market.setup-score.presets.SWING.spot.volume=10",
                        "cryptopilot.market.setup-score.presets.SWING.spot.level=30",
                        "cryptopilot.market.setup-score.presets.SWING.futures.trend=35",
                        "cryptopilot.market.setup-score.presets.SWING.futures.momentum=15",
                        "cryptopilot.market.setup-score.presets.SWING.futures.volume=10",
                        "cryptopilot.market.setup-score.presets.SWING.futures.level=25",
                        "cryptopilot.market.setup-score.presets.SWING.futures.derivatives=15")
                .run(context -> assertThat(context).hasFailed());
        bare.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void BR13_theShippedV2Values_areTheDeclaredDefaults() {
        shipped.run(context -> assertThat(
                        context.getBean(SetupScoreProperties.class).v2())
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(SetupScoreTunings.DEFAULT));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "against-side-factor=1.5",
                "volume-saturation=0.5",
                "funding-span=0",
                "trend-band-sigma=-0.1",
                "level-min-risk-sigma=6",
                "room-cap-score=101",
                "room-cap-to=10",
                "funding-baseline=0.02"
            })
    void BR13_aV2ValueOutOfRange_stopsTheContext(String value) {
        shipped.withPropertyValues(V2 + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void BR13_aMissingV2Value_stopsTheContext() {
        shipped.withPropertyValues(V2 + "room-cap-from=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void BR13_aRoomCapNotBelowTheLowestAlertThreshold_stopsTheContext() {
        shipped.withPropertyValues(V2 + "room-cap-score=55").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("lowest alert threshold 55"));
        shipped.withPropertyValues(
                        V2 + "room-cap-score=60",
                        "cryptopilot.user.risk-profiles.profiles.AGGRESSIVE.min-setup-score-to-alert=61")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void BR13_requireCapBelow_comparesWithTheLowestThreshold() {
        SetupScoreTunings.DEFAULT.requireCapBelow(List.of(75, 65, 55));
        SetupScoreTunings.DEFAULT.requireCapBelow(List.of());

        assertThatThrownBy(() -> SetupScoreTunings.DEFAULT.requireCapBelow(List.of(75, 54)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertPreset(
            StylePreset preset, String timeframe, ComponentWeights spot, ComponentWeights futures) {
        // v2: the preset version follows the component formulas v2; the weights are unchanged.
        assertThat(preset.version()).isEqualTo("v2");
        assertThat(preset.timeframe()).isEqualTo(timeframe);
        assertThat(preset.spot()).isEqualTo(spot);
        assertThat(preset.futures()).isEqualTo(futures);
    }
}
