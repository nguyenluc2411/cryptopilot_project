package com.cryptopilot.drawing.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A layout as the chart's layout menu lists it.
 *
 * <p>Rule: D-149; T-119.
 *
 * @param id the layout
 * @param pairId the pair it is drawn on
 * @param number its number among the caller's layouts on the pair, from 1; the chart shows "Copy n" while there is no
 *     name
 * @param name the name the Trader gave it, or {@code null}
 * @param drawings how many drawings it holds
 * @param createdAt when it was made
 * @param updatedAt when it was last renamed
 */
public record ChartLayoutResponse(
        UUID id, UUID pairId, int number, String name, long drawings, Instant createdAt, Instant updatedAt) {}
