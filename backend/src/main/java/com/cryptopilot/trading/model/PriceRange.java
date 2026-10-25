package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.PriceSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The prices a pair traded at in one update, the low and high of the forming 1-minute candle so far (ADR-011), or in
 * several updates merged while the matching engine was behind. A range is made of last prices, or of Futures mark
 * prices for liquidation (D-111 E2).
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; D-09.
 *
 * @param market the market
 * @param pairId the pair
 * @param open the first price of the candle the prices belong to, or {@code null} when the source did not give it; it
 *     decides a fill at the open when the candle opened past a level (a gap) and which of two levels one candle
 *     reached came first (D-111 E6, D-112 R4)
 * @param low the lowest price traded
 * @param high the highest price traded, not below {@code low}
 * @param from the open time of the 1-minute candle the prices belong to; a fill reached by the range is recorded at
 *     this time, so a live update and the closed candle replayed later give the same fill (D-78)
 * @param at when the latest update in the range was produced
 * @param closed whether the range holds the candle's final low and high: the candle is closed
 * @param source last prices or mark prices; only ranges of the same source merge
 */
public record PriceRange(
        MarketType market,
        UUID pairId,
        BigDecimal open,
        BigDecimal low,
        BigDecimal high,
        Instant from,
        Instant at,
        boolean closed,
        PriceSource source) {

    public PriceRange {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(source, "source");
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low " + low + " is above high " + high);
        }
        if (from.isAfter(at)) {
            throw new IllegalArgumentException("from " + from + " is after at " + at);
        }
        if (source == PriceSource.MARK && market != MarketType.FUTURES) {
            throw new IllegalArgumentException("only a Futures pair has a mark price");
        }
    }

    /** Last prices of a candle. */
    public PriceRange(
            MarketType market,
            UUID pairId,
            BigDecimal open,
            BigDecimal low,
            BigDecimal high,
            Instant from,
            Instant at,
            boolean closed) {
        this(market, pairId, open, low, high, from, at, closed, PriceSource.LAST);
    }

    /** Last prices of a candle whose open is not known. */
    public PriceRange(
            MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant from, Instant at, boolean closed) {
        this(market, pairId, null, low, high, from, at, closed);
    }

    /** Last prices of a candle still forming, whose open is not known. */
    public PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant from, Instant at) {
        this(market, pairId, low, high, from, at, false);
    }

    /** One update whose candle opened at {@code at}, still forming, whose open is not known. */
    public PriceRange(MarketType market, UUID pairId, BigDecimal low, BigDecimal high, Instant at) {
        this(market, pairId, low, high, at, at, false);
    }

    /**
     * Mark prices of a Futures pair's 1-minute candle: one streamed mark price (low = high, still forming), or a closed
     * mark price candle replayed after downtime.
     */
    public static PriceRange mark(
            UUID pairId, BigDecimal low, BigDecimal high, Instant from, Instant at, boolean closed) {
        return new PriceRange(MarketType.FUTURES, pairId, null, low, high, from, at, closed, PriceSource.MARK);
    }

    /**
     * This range and another of the same pair and source as one: the open of the earlier candle (of either when both
     * share one), the lowest low, the highest high, the earliest and the latest time, closed when either is. Every
     * price either reached is still reached.
     *
     * @throws IllegalArgumentException when the two are of different sources
     */
    public PriceRange mergedWith(PriceRange other) {
        if (source != other.source) {
            throw new IllegalArgumentException("a " + source + " range does not merge with a " + other.source + " one");
        }
        int earlier = from.compareTo(other.from);
        BigDecimal mergedOpen = earlier < 0 ? open : earlier > 0 ? other.open : open != null ? open : other.open;
        return new PriceRange(
                market,
                pairId,
                mergedOpen,
                low.min(other.low),
                high.max(other.high),
                from.isBefore(other.from) ? from : other.from,
                at.isAfter(other.at) ? at : other.at,
                closed || other.closed,
                source);
    }
}
