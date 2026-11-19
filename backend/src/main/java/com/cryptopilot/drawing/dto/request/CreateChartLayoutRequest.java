package com.cryptopilot.drawing.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A new layout on a pair's chart, empty or a copy of another of the caller's layouts on the same pair.
 *
 * <p>Rule: D-149; T-119.
 *
 * @param pairId the pair, listed on at least one market
 * @param name up to 60 characters; left out or blank, the chart shows the layout's number
 * @param copyFrom the caller's layout on the same pair whose drawings the new layout starts with, or {@code null} for
 *     an empty layout
 */
public record CreateChartLayoutRequest(
        @NotNull(message = "MSG01") UUID pairId,
        @Size(max = 60, message = "MSG01") String name,
        UUID copyFrom) {}
