package com.cryptopilot.drawing.service;

import com.cryptopilot.drawing.dto.request.CreateChartDrawingRequest;
import com.cryptopilot.drawing.dto.request.UpdateChartDrawingRequest;
import com.cryptopilot.drawing.dto.response.ChartDrawingResponse;
import java.util.List;
import java.util.UUID;

/**
 * The drawings a Trader keeps on the chart of each pair. Every method acts on the caller's own drawings only; another
 * Trader's drawing answers as if it did not exist. Implemented by
 * {@link com.cryptopilot.drawing.service.impl.ChartDrawingServiceImpl}.
 *
 * <p>Rule: D-149; T-119.
 */
public interface ChartDrawingService {

    /** The caller's drawings on the pair, oldest first; empty for a pair the caller never drew on. */
    List<ChartDrawingResponse> list(UUID userId, UUID pairId);

    /**
     * Adds a drawing to the pair's chart.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41 when the pair is unknown or not listed
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01 when the anchors or the text do not fit
     *     the type
     * @throws com.cryptopilot.common.exception.BusinessException MSG43 when the pair already holds the maximum number
     *     of the caller's drawings
     */
    ChartDrawingResponse create(UUID userId, CreateChartDrawingRequest request);

    /**
     * Replaces the anchors, style, text and flags of one of the caller's drawings. MSG41 when there is no such
     * drawing; 409 when the request's version is not the stored one.
     */
    ChartDrawingResponse update(UUID userId, UUID drawingId, UpdateChartDrawingRequest request);

    /** Removes one of the caller's drawings; MSG41 when there is no such drawing. */
    void delete(UUID userId, UUID drawingId);

    /** Removes every drawing of the caller on the pair and answers how many were removed. */
    int deleteAll(UUID userId, UUID pairId);
}
