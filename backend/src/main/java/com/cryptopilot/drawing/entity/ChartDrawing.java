package com.cryptopilot.drawing.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.drawing.model.enums.DrawingType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One drawing a Trader put on the chart of a pair. It belongs to the pair, not to a market or a timeframe, so the Spot
 * and Futures charts of a symbol show the same drawings at every timeframe. The anchors and the style are stored as
 * JSON the service has already checked; the type never changes after the drawing is made.
 *
 * <p>Rule: D-149 (4)(5); T-119.
 */
@Getter
@Entity
@Table(name = "chart_drawing")
@AttributeOverride(name = "id", column = @Column(name = "drawing_id", nullable = false, updatable = false))
public class ChartDrawing extends BaseEntity {

    /** Longest text the column holds. */
    public static final int TEXT_MAX_LENGTH = 500;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "drawing_type", nullable = false, updatable = false)
    private DrawingType drawingType;

    /** The anchors, a JSON array of {@code {time, price}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points", nullable = false)
    private String points;

    /** The style, a JSON object. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style", nullable = false)
    private String style;

    /** The text shown with the drawing, or {@code null} for none. */
    @Column(name = "label_text", length = TEXT_MAX_LENGTH)
    private String labelText;

    @Column(name = "is_locked", nullable = false)
    private boolean locked;

    @Column(name = "is_hidden", nullable = false)
    private boolean hidden;

    /** For JPA only. */
    protected ChartDrawing() {}

    private ChartDrawing(UUID userId, UUID pairId, DrawingType drawingType) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        this.drawingType = Objects.requireNonNull(drawingType, "drawingType must not be null");
    }

    /** A new drawing of the Trader on the pair's chart. */
    public static ChartDrawing draw(
            UUID userId,
            UUID pairId,
            DrawingType drawingType,
            String points,
            String style,
            String labelText,
            boolean locked,
            boolean hidden) {
        ChartDrawing drawing = new ChartDrawing(userId, pairId, drawingType);
        drawing.redraw(points, style, labelText, locked, hidden);
        return drawing;
    }

    /** Replaces everything the Trader may change: the anchors, the style, the text and the two flags. */
    public void redraw(String points, String style, String labelText, boolean locked, boolean hidden) {
        this.points = Objects.requireNonNull(points, "points must not be null");
        this.style = Objects.requireNonNull(style, "style must not be null");
        this.labelText = labelText == null || labelText.isBlank() ? null : labelText.strip();
        this.locked = locked;
        this.hidden = hidden;
    }
}
