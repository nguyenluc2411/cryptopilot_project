package com.cryptopilot.support;

import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The closed 1-minute candles a test hands to the matching feed, as the exchange's {@code kline_1m} stream closes them:
 * the candle's open, low and high, its close equal to its high, and its last update one millisecond before the next
 * minute.
 *
 * <p>Rule: NSF-07; D-78.
 */
public final class ClosedKlines {

    private ClosedKlines() {}

    /** The closed candle of {@code pair} on {@code market} that opened at {@code openTime}. */
    public static MinuteKline closed(
            MarketType market, UUID pair, Instant openTime, String open, String low, String high) {
        return new MinuteKline(
                market,
                pair,
                openTime,
                new BigDecimal(low),
                new BigDecimal(high),
                new BigDecimal(high),
                true,
                openTime.plusSeconds(60).minusMillis(1),
                new BigDecimal(open));
    }
}
