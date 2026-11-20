package com.cryptopilot.market.client.fallback;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One OKX one-minute candle, as {@code /api/v5/market/candles} gives it (D-159): display only, never stored.
 *
 * @param openTime when the minute opened
 * @param open the first price
 * @param high the highest price
 * @param low the lowest price
 * @param close the last price
 * @param volume the base volume
 * @param closed whether the minute has ended
 */
public record OkxCandle(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        boolean closed) {}
