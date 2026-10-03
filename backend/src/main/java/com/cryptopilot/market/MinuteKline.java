package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A 1-minute candle of a pair as it stands: from the {@code kline_1m} stream about once a second while it forms and
 * once more when it closes, or a closed one fetched after downtime. Only the prices the matching engine reads are
 * carried: the candle's low and high so far (ADR-011).
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; D-09; ADR-011.
 *
 * @param market the market
 * @param pairId the pair
 * @param openTime when the candle opened (UTC)
 * @param low the lowest price of the candle so far
 * @param high the highest price of the candle so far, not below {@code low}
 * @param closed whether the candle is closed, so its low and high are final
 * @param eventTime when the exchange produced this state of the candle; for a fetched candle, its close time
 */
public record MinuteKline(
        MarketType market,
        UUID pairId,
        Instant openTime,
        BigDecimal low,
        BigDecimal high,
        boolean closed,
        Instant eventTime) {

    public MinuteKline {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
