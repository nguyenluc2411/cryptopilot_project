package com.cryptopilot.drawing.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One anchor of a drawing as the chart sends it.
 *
 * <p>Rule: D-149 (4); T-119.
 *
 * @param time the open time of the candle the anchor is on, which may be after the last candle
 * @param price the price, greater than 0
 */
public record ChartPointRequest(
        @NotNull(message = "MSG01") Instant time,

        @NotNull(message = "MSG01") @Positive(message = "MSG15") @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price) {}
