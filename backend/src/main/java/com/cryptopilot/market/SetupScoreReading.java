package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.SetupStyle;
import java.time.Instant;
import java.util.Objects;

/**
 * The setup score of a pair's latest closed candle on one timeframe, read under one style preset, for a module that
 * stores it beside a plan or a fill.
 *
 * <p>Rule: BR-13, BR-70; TECHNICAL_DESIGN 7.4; D-53, D-112.
 *
 * @param score the weighted score 0–100, or {@code null} when it cannot be read: the candle's row not stored (a row of
 *     an older candle never stands in for it), a component missing, a preset version whose weights are no longer
 *     configured, or a timeframe other than the preset's own
 * @param style the preset
 * @param presetVersion the preset version the score was asked for, e.g. {@code v1}
 * @param timeframe the timeframe the score was asked on, as BR-08 spells it
 * @param openTime the open time of the candle the score is for: the latest one closed at the instant of the read, also
 *     when its row is not stored
 */
public record SetupScoreReading(
        Integer score, SetupStyle style, String presetVersion, String timeframe, Instant openTime) {

    public SetupScoreReading {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(presetVersion, "presetVersion");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(openTime, "openTime");
    }

    /**
     * The preset name and version as a plan stores them, e.g. {@code DAY_TRADING v1}; {@link SetupScorePreset#parse}
     * reads it back.
     */
    public String preset() {
        return new SetupScorePreset(style, presetVersion).format();
    }
}
