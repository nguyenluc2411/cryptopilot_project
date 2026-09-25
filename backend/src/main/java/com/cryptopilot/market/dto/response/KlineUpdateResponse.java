package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The payload of {@code /topic/kline.{market}.{symbol}.{tf}}: a candle of a stored timeframe as the stream reports it,
 * forming ({@code closed} false, provisional on the chart of SCR-10) or closed. Decimals are strings.
 *
 * <p>Rule: NSF-03; BR-08; SRS 3.3.2; TECHNICAL_DESIGN 9; D-51.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param symbol the symbol
 * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param openTime the open time, which identifies the candle
 * @param closeTime the close time
 * @param open the open price
 * @param high the high price so far
 * @param low the low price so far
 * @param close the latest price, or the close once closed
 * @param baseVolume the volume in the base asset so far
 * @param quoteVolume the volume in the quote asset so far
 * @param closed whether the exchange has closed the candle
 * @param sourceTime the instant the exchange stamped the message
 */
public record KlineUpdateResponse(
        String market,
        String symbol,
        String timeframe,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal baseVolume,
        BigDecimal quoteVolume,
        boolean closed,
        Instant sourceTime) {}
