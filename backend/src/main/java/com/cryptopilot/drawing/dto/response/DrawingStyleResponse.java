package com.cryptopilot.drawing.dto.response;

import com.cryptopilot.drawing.model.enums.LineStyle;
import java.math.BigDecimal;
import java.util.List;

/**
 * How a drawing looks, as the chart paints it.
 *
 * <p>Rule: D-149 (3); T-119.
 *
 * @param lineColor the stroke colour, {@code #RRGGBB} or {@code #RRGGBBAA}
 * @param lineWidth the stroke width in pixels
 * @param lineStyle solid, dashed or dotted
 * @param fillColor the fill of a zone, or {@code null} for none
 * @param extendLeft whether a line is drawn past its first anchor to the left edge
 * @param extendRight whether a line or zone is drawn past its last anchor to the right edge
 * @param fontSize the size of the text in pixels, or {@code null} for the default
 * @param fibLevels the Fibonacci ratios shown, or {@code null} for the default set of the tool
 */
public record DrawingStyleResponse(
        String lineColor,
        int lineWidth,
        LineStyle lineStyle,
        String fillColor,
        boolean extendLeft,
        boolean extendRight,
        Integer fontSize,
        List<BigDecimal> fibLevels) {}
