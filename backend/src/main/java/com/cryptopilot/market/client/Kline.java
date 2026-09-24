package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One candle as the exchange reports it.
 *
 * <p>Every price and volume is a {@link BigDecimal} parsed from the exchange's string, never through a
 * {@code double} (ADR-008). The REST response includes the candle that is still forming, so a candle is
 * not a fact until {@link #isClosedAt} says so; only closed candles may be stored (BR-08).
 *
 * <p>Rule: BR-08, BR-09; NSF-02; TECHNICAL_DESIGN 5.4.
 *
 * @param openTime the instant the candle opened (UTC)
 * @param closeTime the last millisecond of the candle, as the exchange reports it
 * @param open the first price
 * @param high the highest price
 * @param low the lowest price
 * @param close the last price
 * @param volume the base-asset volume
 * @param quoteVolume the quote-asset volume
 * @param tradeCount the number of trades
 */
public record Kline(
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal quoteVolume,
        long tradeCount) {

    /**
     * Whether the candle had closed at this instant: its close time is strictly before it. A candle
     * whose last millisecond has not yet passed may still change and is not stored (BR-08).
     *
     * <p>Rule: BR-08.
     */
    public boolean isClosedAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return closeTime.isBefore(now);
    }
}
