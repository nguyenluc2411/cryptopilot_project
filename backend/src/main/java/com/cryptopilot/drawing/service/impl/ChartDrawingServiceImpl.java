package com.cryptopilot.drawing.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.drawing.dto.request.ChartPointRequest;
import com.cryptopilot.drawing.dto.request.CreateChartDrawingRequest;
import com.cryptopilot.drawing.dto.request.DrawingStyleRequest;
import com.cryptopilot.drawing.dto.request.UpdateChartDrawingRequest;
import com.cryptopilot.drawing.dto.response.ChartDrawingResponse;
import com.cryptopilot.drawing.entity.ChartDrawing;
import com.cryptopilot.drawing.model.ChartPoint;
import com.cryptopilot.drawing.model.DrawingStyle;
import com.cryptopilot.drawing.repository.ChartDrawingRepository;
import com.cryptopilot.drawing.repository.ChartLayoutRepository;
import com.cryptopilot.drawing.service.ChartDrawingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chart drawing use cases.
 *
 * <p>Ownership is part of every query: a drawing is looked up by its key <em>and</em> the caller, so another Trader's
 * drawing is "not found" (MSG41) rather than "forbidden", which would confirm that the key exists.
 *
 * <p>A drawing goes into one of the caller's layouts. A create counts the layout's drawings and inserts the new one
 * under the caller's lock of this module, so two concurrent creates at {@code max − 1} cannot both pass
 * {@link #MAX_PER_LAYOUT} (D-63). An update carries the
 * version the chart last read and is refused with 409 when the drawing changed since, so a drag in one tab does not
 * silently undo a change made in another.
 *
 * <p>Rule: D-149 (5); D-63; T-119.
 *
 * <p>Reference: OWASP Foundation (2023). <i>API Security Top 10</i>, API1:2023 "Broken Object Level Authorization".
 * Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Optimistic Offline
 * Lock".
 */
@Service
@RequiredArgsConstructor
public class ChartDrawingServiceImpl implements ChartDrawingService {

    /** The most drawings one layout holds (D-149). */
    static final int MAX_PER_LAYOUT = 200;

    /** The scope of the per-user lock the creates of this module take, apart from the other modules' writes. */
    static final String LOCK_SCOPE = "chart-drawing";

    private static final TypeReference<List<ChartPoint>> POINTS = new TypeReference<>() {};

    private final ChartDrawingRepository drawings;
    private final ChartLayoutRepository layouts;
    private final UserLock lock;
    private final JsonMapper json;

    @Override
    @Transactional(readOnly = true)
    public List<ChartDrawingResponse> list(UUID userId, UUID layoutId) {
        return drawings.findByUserIdAndLayoutIdOrderByCreatedAtAscIdAsc(userId, layoutId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ChartDrawingResponse create(UUID userId, CreateChartDrawingRequest request) {
        if (layouts.findByIdAndUserId(request.layoutId(), userId).isEmpty()) {
            throw new ResourceNotFoundException("ChartLayout", request.layoutId());
        }
        List<ChartPoint> points = points(request.points());
        DrawingRules.check(request.type(), points, request.text());
        lock.lock(LOCK_SCOPE, userId);
        long held = drawings.countByLayoutId(request.layoutId());
        if (held >= MAX_PER_LAYOUT) {
            throw new BusinessException(
                    ErrorCode.CHART_DRAWING_LIMIT_REACHED,
                    "layout " + request.layoutId() + " already holds " + held + " drawings",
                    MAX_PER_LAYOUT);
        }
        ChartDrawing drawing = ChartDrawing.draw(
                userId,
                request.layoutId(),
                request.type(),
                json.writeValueAsString(points),
                json.writeValueAsString(style(request.style())),
                request.text(),
                on(request.locked()),
                on(request.hidden()));
        drawings.save(drawing);
        drawings.flush();
        return toResponse(drawing);
    }

    @Override
    @Transactional
    public ChartDrawingResponse update(UUID userId, UUID drawingId, UpdateChartDrawingRequest request) {
        ChartDrawing drawing = owned(userId, drawingId);
        if (drawing.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(ChartDrawing.class, drawingId);
        }
        List<ChartPoint> points = points(request.points());
        DrawingRules.check(drawing.getDrawingType(), points, request.text());
        drawing.redraw(
                json.writeValueAsString(points),
                json.writeValueAsString(style(request.style())),
                request.text(),
                on(request.locked()),
                on(request.hidden()));
        drawings.flush();
        return toResponse(drawing);
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID drawingId) {
        drawings.delete(owned(userId, drawingId));
    }

    @Override
    @Transactional
    public int deleteAll(UUID userId, UUID layoutId) {
        return drawings.deleteByOwnerAndLayout(userId, layoutId);
    }

    private ChartDrawing owned(UUID userId, UUID drawingId) {
        return drawings.findByIdAndUserId(drawingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ChartDrawing", drawingId));
    }

    private static List<ChartPoint> points(List<ChartPointRequest> points) {
        return points.stream()
                .map(point -> new ChartPoint(point.time(), point.price().stripTrailingZeros()))
                .toList();
    }

    private static DrawingStyle style(DrawingStyleRequest style) {
        return new DrawingStyle(
                style.lineColor().toUpperCase(Locale.ROOT),
                style.lineWidth(),
                style.lineStyle(),
                style.fillColor() == null ? null : style.fillColor().toUpperCase(Locale.ROOT),
                on(style.extendLeft()),
                on(style.extendRight()),
                style.fontSize(),
                style.fibLevels() == null
                        ? null
                        : style.fibLevels().stream()
                                .map(BigDecimal::stripTrailingZeros)
                                .toList());
    }

    /** A flag the client may leave out, which then means off. */
    private static boolean on(Boolean flag) {
        return Boolean.TRUE.equals(flag);
    }

    private ChartDrawingResponse toResponse(ChartDrawing drawing) {
        return new ChartDrawingResponse(
                drawing.getId(),
                drawing.getLayoutId(),
                drawing.getDrawingType(),
                json.readValue(drawing.getPoints(), POINTS),
                json.readValue(drawing.getStyle(), DrawingStyle.class),
                drawing.getLabelText(),
                drawing.isLocked(),
                drawing.isHidden(),
                drawing.getVersion(),
                drawing.getCreatedAt(),
                drawing.getUpdatedAt());
    }
}
