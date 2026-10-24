package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.SetupStyle;
import java.util.Objects;
import java.util.Optional;

/**
 * A style preset and its version as another module stores them beside a setup score, e.g. {@code DAY_TRADING v1}: the
 * one place that writes that value and reads it back, so a stored score can be read again under the preset it was
 * stored with.
 *
 * <p>Rule: BR-13, BR-70; D-53, D-112.
 *
 * @param style the preset
 * @param version the preset version, e.g. {@code v1}; never blank and without whitespace
 */
public record SetupScorePreset(SetupStyle style, String version) {

    private static final String SEPARATOR = " ";

    public SetupScorePreset {
        Objects.requireNonNull(style, "style");
        if (!isVersion(version)) {
            throw new IllegalArgumentException("a preset version is not blank and has no whitespace: " + version);
        }
    }

    /**
     * Reads a stored value back into its preset and version.
     *
     * @param stored the value as {@link #format()} wrote it
     * @return the preset, or empty for {@code null}, an unknown style or any other shape than the style name, one space
     *     and the version
     */
    public static Optional<SetupScorePreset> parse(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        int separator = stored.indexOf(SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        String version = stored.substring(separator + SEPARATOR.length());
        return style(stored.substring(0, separator))
                .filter(style -> isVersion(version))
                .map(style -> new SetupScorePreset(style, version));
    }

    /** The value as it is stored, e.g. {@code DAY_TRADING v1}. */
    public String format() {
        return style.name() + SEPARATOR + version;
    }

    private static Optional<SetupStyle> style(String name) {
        for (SetupStyle style : SetupStyle.values()) {
            if (style.name().equals(name)) {
                return Optional.of(style);
            }
        }
        return Optional.empty();
    }

    private static boolean isVersion(String version) {
        return version != null && !version.isEmpty() && version.codePoints().noneMatch(Character::isWhitespace);
    }
}
