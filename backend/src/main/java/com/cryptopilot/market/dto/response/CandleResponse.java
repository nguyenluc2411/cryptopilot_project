package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One closed candle (BR-08). Times are UTC; prices and volumes are decimals sent as strings.
 *
 * <p>Rule: UC-09, BR-08.
 *
 * @param openTime the open time, which identifies the candle
 * @param closeTime the close time
 * @param open the open price
 * @param high the high price
 * @param low the low price
 * @param close the close price
 * @param baseVolume the volume in the base asset
 * @param quoteVolume the volume in the quote asset
 * @param tradeCount the number of trades, or absent when the source sent none
 */
public record CandleResponse(
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal baseVolume,
        BigDecimal quoteVolume,
        Integer tradeCount) {}
