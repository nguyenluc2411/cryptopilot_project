package com.cryptopilot.market.config;

import com.cryptopilot.market.SetupStyle;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.StylePreset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The style presets of the setup score, under {@code cryptopilot.market.setup-score}. Every preset must be declared
 * and valid, or the application does not start.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53 (rule 6: weights live in configuration).
 * <p>Reference: Weights per D-53 / ADR-013.
 *
 * @param presets each preset's version, timeframe and weights per market
 */
@ConfigurationProperties("cryptopilot.market.setup-score")
public record SetupScoreProperties(Map<SetupStyle, Preset> presets) {

    /**
     * One preset as declared.
     *
     * @param version the preset version
     * @param timeframe the timeframe it reads
     * @param spot the Spot weights
     * @param futures the Futures weights
     */
    public record Preset(String version, String timeframe, ComponentWeights spot, ComponentWeights futures) {}

    public SetupScoreProperties {
        Objects.requireNonNull(presets, "cryptopilot.market.setup-score.presets must be declared");
        EnumSet<SetupStyle> missing = EnumSet.allOf(SetupStyle.class);
        missing.removeAll(presets.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("setup score presets not declared: " + missing);
        }
        Map<SetupStyle, Preset> copy = new EnumMap<>(SetupStyle.class);
        presets.forEach((style, preset) -> {
            toStylePreset(style, preset);
            copy.put(style, preset);
        });
        presets = Map.copyOf(copy);
    }

    /** The validated preset {@code style}. */
    public StylePreset preset(SetupStyle style) {
        return toStylePreset(style, presets.get(style));
    }

    private static StylePreset toStylePreset(SetupStyle style, Preset preset) {
        Objects.requireNonNull(preset, style + " is declared empty");
        return new StylePreset(style, preset.version(), preset.timeframe(), preset.spot(), preset.futures());
    }
}
