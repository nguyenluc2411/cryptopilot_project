package com.cryptopilot.drawing.service;

import com.cryptopilot.drawing.dto.request.CreateChartDrawingRequest;
import com.cryptopilot.drawing.dto.request.UpdateChartDrawingRequest;
import com.cryptopilot.drawing.dto.response.ChartDrawingResponse;
import java.util.List;
import java.util.UUID;

/**
 * The drawings a Trader keeps in each layout of a pair's chart. Every method acts on the caller's own drawings only; another
 * Trader's drawing answers as if it did not exist. Implemented by
 * {@link com.cryptopilot.drawing.service.impl.ChartDrawingServiceImpl}.
 *
 * <p>Rule: D-149; T-119.
 */
public interface ChartDrawingService {

    /** The caller's drawings in the layout, oldest first; empty for a layout that is not the caller's. */
    List<ChartDrawingResponse> list(UUID userId, UUID layoutId);

    /**
     * Adds a drawing to one of the caller's layouts.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41 when the layout is not the caller's
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01 when the anchors or the text do not fit
     *     the type
     * @throws com.cryptopilot.common.exception.BusinessException MSG43 when the layout already holds the maximum number
     *     of drawings
     */
    ChartDrawingResponse create(UUID userId, CreateChartDrawingRequest request);

    /**
     * Replaces the anchors, style, text and flags of one of the caller's drawings. MSG41 when there is no such
     * drawing; 409 when the request's version is not the stored one.
     */
    ChartDrawingResponse update(UUID userId, UUID drawingId, UpdateChartDrawingRequest request);

    /** Removes one of the caller's drawings; MSG41 when there is no such drawing. */
    void delete(UUID userId, UUID drawingId);

    /** Removes every drawing of the caller in the layout and answers how many were removed. */
    int deleteAll(UUID userId, UUID layoutId);
}
