package com.cryptopilot.paper.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.FillSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The prices one 1-minute candle of a pair has traded so far, as the paper matching engine reads them: its low and
 * high, live from the stream or replayed from a closed candle (ADR-011).
 *
 * <p>Rule: TR-02; NSF-07; Q-T6; D-78; ADR-011.
 *
 * @param market the market
 * @param pairId the pair
 * @param low the lowest price of the candle so far
 * @param high the highest price of the candle so far
 * @param from the candle's open time, which a fill it decides is recorded at (D-78)
 * @param closed whether the candle is closed, so its low and high are final
 * @param source live or replayed
 */
public record PriceRange(
        MarketType market,
        UUID pairId,
        BigDecimal low,
        BigDecimal high,
        Instant from,
        boolean closed,
        FillSource source) {

    public PriceRange {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(source, "source");
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low " + low + " is above high " + high);
        }
    }

    /**
     * This range followed by a later update of the same pair: of the same candle, one range with the lowest low and
     * the highest high, closed when either is; of a later candle, the later one, whose own closed update carries the
     * earlier candle in full.
     */
    public PriceRange mergedWith(PriceRange later) {
        if (!later.from.equals(from)) {
            return later.from.isAfter(from) ? later : this;
        }
        return new PriceRange(
                market, pairId, low.min(later.low), high.max(later.high), from, closed || later.closed, source);
    }
}
