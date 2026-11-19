package com.cryptopilot.drawing.service;

import com.cryptopilot.drawing.dto.request.CreateChartLayoutRequest;
import com.cryptopilot.drawing.dto.request.RenameChartLayoutRequest;
import com.cryptopilot.drawing.dto.response.ChartLayoutResponse;
import java.util.List;
import java.util.UUID;

/**
 * The layouts a Trader keeps on the chart of each pair, each a set of drawings. Every method acts on the caller's own
 * layouts only; another Trader's layout answers as if it did not exist. Implemented by
 * {@link com.cryptopilot.drawing.service.impl.ChartLayoutServiceImpl}.
 *
 * <p>Rule: D-149; T-119.
 */
public interface ChartLayoutService {

    /** The caller's layouts on the pair by number; empty for a pair the caller never drew on. */
    List<ChartLayoutResponse> list(UUID userId, UUID pairId);

    /**
     * Opens a new layout on the pair under the smallest number still free, empty or with a copy of another layout's
     * drawings.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41 when the pair is unknown or not listed,
     *     or the layout to copy is not one of the caller's
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01 when the layout to copy is on another pair
     * @throws com.cryptopilot.common.exception.BusinessException MSG43 when the caller already keeps the most layouts
     *     allowed on the pair
     */
    ChartLayoutResponse create(UUID userId, CreateChartLayoutRequest request);

    /** Renames one of the caller's layouts; MSG41 when there is no such layout. */
    ChartLayoutResponse rename(UUID userId, UUID layoutId, RenameChartLayoutRequest request);

    /** Removes one of the caller's layouts with its drawings; MSG41 when there is no such layout. */
    void delete(UUID userId, UUID layoutId);
}
