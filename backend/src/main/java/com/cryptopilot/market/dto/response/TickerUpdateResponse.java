package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The payload of {@code /topic/ticker.{market}.{symbol}}: the latest values of a pair, with the exchange's instant so a
 * client can tell their age (SRS 3.3.1, Live/Delayed). A Spot update carries the ticker fields, a futures update the
 * mark price fields; the others are absent. Decimals are strings.
 *
 * <p>Rule: NSF-03; BR-11; SRS 3.3.1, 4.2.3; TECHNICAL_DESIGN 9; D-51.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param symbol the symbol
 * @param sourceTime the instant the exchange stamped the values
 * @param lastPrice the last traded price (Spot)
 * @param bestBid the best bid (Spot)
 * @param bestAsk the best ask (Spot)
 * @param high24h the 24-hour high (Spot)
 * @param low24h the 24-hour low (Spot)
 * @param changePercent24h the 24-hour change in percent (Spot)
 * @param baseVolume24h the 24-hour volume in the base asset (Spot)
 * @param quoteVolume24h the 24-hour volume in the quote asset (Spot)
 * @param markPrice the mark price (futures)
 * @param indexPrice the index price (futures)
 * @param fundingRate the predicted funding rate (futures)
 * @param nextFundingTime the next settlement, as the source reported it (futures)
 */
public record TickerUpdateResponse(
        String market,
        String symbol,
        Instant sourceTime,
        BigDecimal lastPrice,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal high24h,
        BigDecimal low24h,
        BigDecimal changePercent24h,
        BigDecimal baseVolume24h,
        BigDecimal quoteVolume24h,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal fundingRate,
        Instant nextFundingTime) {}
