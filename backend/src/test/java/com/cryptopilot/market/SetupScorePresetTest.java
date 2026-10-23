package com.cryptopilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.model.enums.SetupStyle;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SetupScorePresetTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(SetupStyle.class)
    void BR70_everyPreset_isReadBackAsItWasStored(SetupStyle style) {
        SetupScorePreset preset = new SetupScorePreset(style, "v1");

        assertThat(SetupScorePreset.parse(preset.format())).contains(preset);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SetupStyle.class)
    void BR70_theValueAPlanStores_isReadBackIntoItsStyleAndVersion(SetupStyle style) {
        String stored = new SetupScoreReading(59, style, "v2", "1h", Instant.EPOCH).preset();

        assertThat(stored).isEqualTo(style.name() + " v2");
        assertThat(SetupScorePreset.parse(stored)).contains(new SetupScorePreset(style, "v2"));
    }

    @Test
    void BR70_aStoredDayTradingPreset_isParsed() {
        assertThat(SetupScorePreset.parse("DAY_TRADING v1"))
                .contains(new SetupScorePreset(SetupStyle.DAY_TRADING, "v1"));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "POSITION v1",
                "day_trading v1",
                "DAY_TRADING",
                "DAY_TRADING ",
                "DAY_TRADING  v1",
                " DAY_TRADING v1",
                "DAY_TRADING v1 ",
                "DAY_TRADING v1 v2",
                "DAY_TRADING\tv1",
                "DAY_TRADING-v1",
                "v1"
            })
    void BR70_anUnknownOrMalformedStoredValue_isEmpty_notAnException(String stored) {
        assertThat(SetupScorePreset.parse(stored)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "v 1"})
    void BR70_aVersionThatCouldNotBeReadBack_isRefused(String version) {
        assertThatThrownBy(() -> new SetupScorePreset(SetupStyle.SWING, version))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
