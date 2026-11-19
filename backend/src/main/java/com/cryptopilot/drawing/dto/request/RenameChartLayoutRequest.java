package com.cryptopilot.drawing.dto.request;

import jakarta.validation.constraints.Size;

/**
 * The new name of a layout.
 *
 * <p>Rule: D-149; T-119.
 *
 * @param name up to 60 characters; {@code null} or blank removes the name, so the chart shows the number again
 */
public record RenameChartLayoutRequest(
        @Size(max = 60, message = "MSG01") String name) {}
