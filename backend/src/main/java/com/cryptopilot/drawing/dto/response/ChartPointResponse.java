package com.cryptopilot.drawing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One anchor of a drawing as the chart places it.
 *
 * <p>Rule: D-149 (4); T-119.
 *
 * @param time the candle open time
 * @param price the price, without trailing zeros
 */
public record ChartPointResponse(Instant time, BigDecimal price) {}
