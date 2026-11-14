package com.cryptopilot.drawing.dto.request;

import com.cryptopilot.drawing.model.enums.LineStyle;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * The style of a drawing as the chart sends it. Only these values are stored: the service rebuilds the stored style
 * from them, so any other property a client sends never reaches the database.
 *
 * <p>Rule: D-149 (3); T-119.
 *
 * @param lineColor {@code #RRGGBB} or {@code #RRGGBBAA}
 * @param lineWidth 1 to 4 pixels
 * @param lineStyle solid, dashed or dotted
 * @param fillColor {@code #RRGGBB} or {@code #RRGGBBAA}, or {@code null} for no fill
 * @param extendLeft whether a line goes on past its first anchor; false when left out
 * @param extendRight whether a line or zone goes on past its last anchor; false when left out
 * @param fontSize 8 to 48 pixels, or {@code null} for the default
 * @param fibLevels up to 24 ratios between -10 and 10, or {@code null} for the tool's default set
 */
public record DrawingStyleRequest(
        @NotNull(message = "MSG01") @Pattern(regexp = COLOR, message = "MSG01")
        String lineColor,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG01") @Max(value = 4, message = "MSG01")
        Integer lineWidth,

        @NotNull(message = "MSG01") LineStyle lineStyle,
        @Pattern(regexp = COLOR, message = "MSG01") String fillColor,
        Boolean extendLeft,
        Boolean extendRight,

        @Min(value = 8, message = "MSG01") @Max(value = 48, message = "MSG01")
        Integer fontSize,

        @Size(max = 24, message = "MSG01")
        List<
                        @NotNull(message = "MSG01") @DecimalMin(value = "-10", message = "MSG01")
                        @DecimalMax(value = "10", message = "MSG01")
                        @Digits(integer = 2, fraction = 4, message = "MSG01") BigDecimal>
                fibLevels) {

    /** A hex colour with an optional alpha byte. */
    public static final String COLOR = "^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$";
}
