package com.cryptopilot.drawing.dto.request;

import com.cryptopilot.drawing.model.enums.DrawingType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The new state of a drawing after the Trader moved, restyled, locked or hid it; every field is replaced. The version
 * is the one the chart last read: when the drawing was changed since, nothing is written and 409 answers.
 *
 * <p>Rule: D-149 (3)(5); T-119.
 *
 * @param version the version the chart last read
 * @param points the anchors, as many as the drawing's type needs
 * @param style how it looks
 * @param text up to 500 characters; required for a text drawing
 * @param locked whether the chart refuses to move it; false when left out
 * @param hidden whether the chart leaves it out; false when left out
 */
public record UpdateChartDrawingRequest(
        @NotNull(message = "MSG01") @PositiveOrZero(message = "MSG01")
        Long version,

        @NotNull(message = "MSG01") @Size(min = 1, max = DrawingType.MAX_ANCHORS, message = "MSG01")
        List<@NotNull(message = "MSG01") @Valid ChartPointRequest> points,

        @NotNull(message = "MSG01") @Valid DrawingStyleRequest style,
        @Size(max = 500, message = "MSG01") String text,
        Boolean locked,
        Boolean hidden) {}
