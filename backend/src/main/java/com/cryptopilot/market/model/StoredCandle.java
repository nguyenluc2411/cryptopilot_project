package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One closed candle as {@code ohlcv} holds it.
 *
 * <p>Rule: BR-08; UC-09.
 *
 * @param openTime the open time, which identifies the candle
 * @param closeTime the close time
 * @param open the open price
 * @param high the high price
 * @param low the low price
 * @param close the close price
 * @param baseVolume the volume in the base asset
 * @param quoteVolume the volume in the quote asset
 * @param tradeCount the number of trades, or {@code null} when the source sent none
 */
public record StoredCandle(
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal baseVolume,
        BigDecimal quoteVolume,
        Integer tradeCount) {}
