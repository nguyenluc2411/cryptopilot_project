package com.cryptopilot.drawing.dto.request;

import com.cryptopilot.drawing.model.enums.DrawingType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A new drawing in one of the Trader's chart layouts. The number of anchors must be the type's, and a position's stop
 * and target must lie on the right sides of its entry; the service checks both.
 *
 * <p>Rule: D-149 (2)(4)(5); T-119.
 *
 * @param layoutId the caller's layout the drawing goes into
 * @param type the tool
 * @param points the anchors, 1 to 7 as the type needs
 * @param style how it looks
 * @param text up to 500 characters; required for a {@link DrawingType#TEXT}, optional for the others
 * @param locked whether the chart refuses to move it; false when left out
 * @param hidden whether the chart leaves it out; false when left out
 */
public record CreateChartDrawingRequest(
        @NotNull(message = "MSG01") UUID layoutId,
        @NotNull(message = "MSG01") DrawingType type,

        @NotNull(message = "MSG01") @Size(min = 1, max = DrawingType.MAX_ANCHORS, message = "MSG01")
        List<@NotNull(message = "MSG01") @Valid ChartPointRequest> points,

        @NotNull(message = "MSG01") @Valid DrawingStyleRequest style,
        @Size(max = 500, message = "MSG01") String text,
        Boolean locked,
        Boolean hidden) {}
