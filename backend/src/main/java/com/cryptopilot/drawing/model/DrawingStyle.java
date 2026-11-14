package com.cryptopilot.drawing.model;

import com.cryptopilot.drawing.model.enums.LineStyle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * How a drawing looks. Only these values are stored, so a client cannot keep arbitrary data in a drawing.
 *
 * <p>Rule: D-149 (3); T-119.
 *
 * @param lineColor the stroke colour, {@code #RRGGBB} or {@code #RRGGBBAA}
 * @param lineWidth the stroke width in pixels, 1 to 4
 * @param lineStyle solid, dashed or dotted
 * @param fillColor the fill of a zone, or {@code null} for none
 * @param extendLeft whether a line is drawn past its first anchor to the left edge
 * @param extendRight whether a line or zone is drawn past its last anchor to the right edge
 * @param fontSize the size of the text in pixels, or {@code null} for the default
 * @param fibLevels the Fibonacci ratios shown, or {@code null} for the default set of the tool
 */
public record DrawingStyle(
        String lineColor,
        int lineWidth,
        LineStyle lineStyle,
        String fillColor,
        boolean extendLeft,
        boolean extendRight,
        Integer fontSize,
        List<BigDecimal> fibLevels) {

    public DrawingStyle {
        Objects.requireNonNull(lineColor, "lineColor");
        Objects.requireNonNull(lineStyle, "lineStyle");
        fibLevels = fibLevels == null ? null : List.copyOf(fibLevels);
    }
}
