package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A 1-minute candle of a pair as it stands: from the {@code kline_1m} stream about once a second while it forms and
 * once more when it closes, or a closed one fetched after downtime. It carries the prices its listeners read: the
 * candle's low and high so far for the matching engine (ADR-011), and its close, the last traded price, for price
 * alerts (BR-18).
 *
 * <p>Rule: NSF-06, NSF-07, BR-18; TECHNICAL_DESIGN 7.7, 7.9; D-09; ADR-011.
 *
 * @param market the market
 * @param pairId the pair
 * @param openTime when the candle opened (UTC)
 * @param low the lowest price of the candle so far
 * @param high the highest price of the candle so far, not below {@code low}
 * @param close the last price of the candle so far: the latest trade while it forms, its close once closed
 * @param closed whether the candle is closed, so its low and high are final
 * @param eventTime when the exchange produced this state of the candle; for a fetched candle, its close time
 */
public record MinuteKline(
        MarketType market,
        UUID pairId,
        Instant openTime,
        BigDecimal low,
        BigDecimal high,
        BigDecimal close,
        boolean closed,
        Instant eventTime) {

    public MinuteKline {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
