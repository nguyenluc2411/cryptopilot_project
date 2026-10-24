package com.cryptopilot.trading.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.SetupStyle;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The analysis timeframes of a plan and their default per style preset. */
class AnalysisTimeframeTest {

    @Test
    void BR08_theTimeframes_areTheFourAnalysisTimeframes() {
        assertThat(Arrays.stream(AnalysisTimeframe.values()).map(AnalysisTimeframe::code))
                .containsExactly("15m", "1h", "4h", "1d");
    }

    @ParameterizedTest
    @ValueSource(strings = {"15m", "1h", "4h", "1d"})
    void BR08_eachCode_readsBackItsTimeframe(String code) {
        assertThat(AnalysisTimeframe.fromCode(code))
                .hasValueSatisfying(tf -> assertThat(tf.code()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1m", "5m", "1w", "1H", ""})
    void BR08_anyOtherCode_isNoTimeframe(String code) {
        assertThat(AnalysisTimeframe.fromCode(code)).isEmpty();
    }

    @Test
    void BR08_noCode_isNoTimeframe() {
        assertThat(AnalysisTimeframe.fromCode(null)).isEmpty();
    }

    /** SCALPING 15m, DAY_TRADING 1h, SWING 4h; POSITION reads SWING in the profile's port, so it is 4h too. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"SCALPING, 15m", "DAY_TRADING, 1h", "SWING, 4h"})
    void D112_theDefault_isThePrimaryTimeframeOfThePreset(SetupStyle style, String expected) {
        assertThat(AnalysisTimeframe.defaultFor(Optional.of(style)).code()).isEqualTo(expected);
    }

    @Test
    void D112_withoutAPreset_theDefaultIsOneHour() {
        assertThat(AnalysisTimeframe.defaultFor(Optional.empty())).isEqualTo(AnalysisTimeframe.ONE_HOUR);
    }
}
