package com.cryptopilot.drawing.dto.response;

import com.cryptopilot.drawing.model.ChartPoint;
import com.cryptopilot.drawing.model.DrawingStyle;
import com.cryptopilot.drawing.model.enums.DrawingType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A drawing as the chart paints it. The chart sends {@code version} back with its next change.
 *
 * <p>Rule: D-149 (5); T-119.
 *
 * @param id the drawing
 * @param pairId the pair it is drawn on
 * @param type the tool
 * @param points the anchors
 * @param style how it looks
 * @param text the text, or {@code null}
 * @param locked whether the chart refuses to move it
 * @param hidden whether the chart leaves it out
 * @param version the optimistic locking version
 * @param createdAt when it was drawn
 * @param updatedAt when it was last changed
 */
public record ChartDrawingResponse(
        UUID id,
        UUID pairId,
        DrawingType type,
        List<ChartPoint> points,
        DrawingStyle style,
        String text,
        boolean locked,
        boolean hidden,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
