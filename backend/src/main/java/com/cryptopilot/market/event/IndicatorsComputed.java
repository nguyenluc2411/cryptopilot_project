package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * NSF-05 has stored the indicators of a closed candle. Published inside the transaction that writes the row, so a
 * transactional listener hears it only once the row is committed. Carries the values of this candle and of the one
 * before it in the same series, as stored (10 places), so a listener can tell a cross without reading the table.
 *
 * <p>Like {@link CandleClosed}, it can be published again for a candle already processed; listeners must be
 * idempotent.
 *
 * <p>Exposed in the named interface {@code events}: the indicator alerts of the {@code watchlist} module are
 * evaluated on it (NSF-06).
 *
 * <p>Rule: NSF-05, NSF-06; BR-12, BR-18; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 *
 * @param pairId the pair
 * @param symbol the pair's symbol
 * @param market the market
 * @param timeframe the timeframe as BR-08 spells it: {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param openTime when the candle opened
 * @param current the values of this candle
 * @param previous the values of the candle before it, all {@code null} when that row is not stored
 */
@NamedInterface("events")
public record IndicatorsComputed(
        UUID pairId,
        String symbol,
        MarketType market,
        String timeframe,
        Instant openTime,
        IndicatorLevels current,
        IndicatorLevels previous) {

    public IndicatorsComputed {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");
    }
}
