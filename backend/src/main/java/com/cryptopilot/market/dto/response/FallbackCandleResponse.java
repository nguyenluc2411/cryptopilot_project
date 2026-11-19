package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One display-only fallback one-minute candle (D-158): drawn apart from the exchange's candles, never stored.
 *
 * @param openTime when the minute opened
 * @param open the first price
 * @param high the highest price
 * @param low the lowest price
 * @param close the last price
 * @param volume the base volume
 * @param closed whether the minute has ended
 */
public record FallbackCandleResponse(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        boolean closed) {}
