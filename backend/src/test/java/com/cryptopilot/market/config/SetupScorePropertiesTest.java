package com.cryptopilot.market.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.SetupStyle;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.StylePreset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The shipped presets are the D-53 v1 table, and a context with an invalid preset does not start.
 *
 * <p>Rule: BR-13; D-53 (rules 4 and 6).
 */
class SetupScorePropertiesTest {

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SetupScoreConfig.class);

    private final ApplicationContextRunner bare =
            new ApplicationContextRunner().withUserConfiguration(SetupScoreConfig.class);

    @Test
    void D53_theShippedPresets_areTheV1Table() {
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

    private static void assertPreset(
            StylePreset preset, String timeframe, ComponentWeights spot, ComponentWeights futures) {
        assertThat(preset.version()).isEqualTo("v1");
        assertThat(preset.timeframe()).isEqualTo(timeframe);
        assertThat(preset.spot()).isEqualTo(spot);
        assertThat(preset.futures()).isEqualTo(futures);
    }
}
