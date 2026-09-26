package com.cryptopilot.market.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.SetupStyle;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A preset is refused unless each market's weights add up to exactly 100 with every weight between 1 and 40.
 *
 * <p>Rule: BR-13; D-53.
 */
class StylePresetTest {

    private static final ComponentWeights SPOT = new ComponentWeights(35, 30, 15, 20, null);
    private static final ComponentWeights FUTURES = new ComponentWeights(30, 25, 15, 15, 15);

    @Test
    void D53_validPreset_returnsTheWeightsOfEachMarket() {
        StylePreset preset = new StylePreset(SetupStyle.DAY_TRADING, "v1", "1h", SPOT, FUTURES);

        assertThat(preset.weights(MarketType.SPOT)).isEqualTo(SPOT);
        assertThat(preset.weights(MarketType.FUTURES)).isEqualTo(FUTURES);
        assertThat(SPOT.total()).isEqualTo(100);
        assertThat(FUTURES.total()).isEqualTo(100);
    }

    @Test
    void D53_weightsNotAddingUpTo100_areRefused() {
        assertThatThrownBy(() -> preset(new ComponentWeights(35, 30, 15, 21, null), FUTURES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add up to 101");
        assertThatThrownBy(() -> preset(SPOT, new ComponentWeights(30, 25, 15, 15, 14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add up to 99");
    }

    @Test
    void D53_aWeightOfZeroOrAbove40_isRefused() {
        assertThatThrownBy(() -> preset(new ComponentWeights(40, 40, 20, 0, null), FUTURES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight 0");
        assertThatThrownBy(() -> preset(new ComponentWeights(41, 29, 15, 15, null), FUTURES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight 41");
        assertThat(preset(new ComponentWeights(40, 1, 19, 40, null), FUTURES)
                        .spot()
                        .trend())
                .isEqualTo(40);
    }

    @Test
    void D53_derivativesOnSpotOrMissingOnFutures_areRefused() {
        assertThatThrownBy(() -> preset(new ComponentWeights(30, 25, 15, 15, 15), FUTURES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preset(SPOT, new ComponentWeights(35, 30, 15, 20, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void D53_aTimeframeBr08DoesNotIngestOrABlankVersion_isRefused() {
        assertThatThrownBy(() -> new StylePreset(SetupStyle.SCALPING, "v1", "5m", SPOT, FUTURES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StylePreset(SetupStyle.SCALPING, " ", "15m", SPOT, FUTURES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StylePreset(SetupStyle.SCALPING, null, "15m", SPOT, FUTURES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR13_spotComponentScores_neverCarryDerivatives() {
        assertThatThrownBy(
                        () -> new ComponentScores(MarketType.SPOT, "1h", "v1", null, null, null, null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StylePreset preset(ComponentWeights spot, ComponentWeights futures) {
        return new StylePreset(SetupStyle.DAY_TRADING, "v1", "1h", spot, futures);
    }
}
