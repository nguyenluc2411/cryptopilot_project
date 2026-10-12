package com.cryptopilot.paper.model;

import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.FillSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The prices a pair has traded over a stretch of time, as the paper matching engine reads them: the low and high of a
 * 1-minute candle, live from the stream or replayed from a closed candle (ADR-011), or of one or more market trades
 * read together (TR-05).
 *
 * <p>A candle's low and high are recorded at its open time (D-78). A trade's price is recorded at the time it was
 * traded, so the trades merged into one range keep the time of the trade at their low and of the one at their high:
 * a BUY the range reached is filled at the low's time, a SELL at the high's.
 *
 * <p>Rule: TR-02, TR-05; NSF-07; Q-T6; D-77, D-78; ADR-011.
 *
 * @param market the market
 * @param pairId the pair
 * @param low the lowest price traded
 * @param high the highest price traded
 * @param from a candle's open time; for trades, the time of the earliest one
 * @param closed whether the candle is closed, so its low and high are final; never for trades
 * @param source live or replayed
 * @param lowAt when the low was traded; a candle's open time
 * @param highAt when the high was traded; a candle's open time
 */
public record PriceRange(
        MarketType market,
        UUID pairId,
        BigDecimal low,
        BigDecimal high,
        Instant from,
        boolean closed,
        FillSource source,
        Instant lowAt,
        Instant highAt) {

    public PriceRange {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lowAt, "lowAt");
        Objects.requireNonNull(highAt, "highAt");
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low " + low + " is above high " + high);
        }
    }

    /** A 1-minute candle's range: its low and high both recorded at its open time (D-78). */
    public PriceRange(
            MarketType market,
            UUID pairId,
            BigDecimal low,
            BigDecimal high,
            Instant from,
            boolean closed,
            FillSource source) {
        this(market, pairId, low, high, from, closed, source, from, from);
    }

    /** One live market trade, at its price and its trade time. */
    public static PriceRange of(MarketTrade trade) {
        return new PriceRange(
                trade.market(),
                trade.pairId(),
                trade.price(),
                trade.price(),
                trade.tradeTime(),
                false,
                FillSource.LIVE,
                trade.tradeTime(),
                trade.tradeTime());
    }

    /**
     * This range followed by a later update of the same pair's candle: of the same candle, one range with the lowest
     * low and the highest high, closed when either is; of a later candle, the later one, whose own closed update
     * carries the earlier candle in full.
     */
    public PriceRange mergedWith(PriceRange later) {
        if (!later.from.equals(from)) {
            return later.from.isAfter(from) ? later : this;
        }
        return new PriceRange(
                market, pairId, low.min(later.low), high.max(later.high), from, closed || later.closed, source);
    }

    /**
     * These trades followed by later ones of the same pair, read as one range: the lowest low and the highest high,
     * each with the time of the earliest trade at it.
     */
    public PriceRange withTrades(PriceRange later) {
        int lower = later.low.compareTo(low);
        int higher = later.high.compareTo(high);
        boolean laterLow = lower < 0 || (lower == 0 && later.lowAt.isBefore(lowAt));
        boolean laterHigh = higher > 0 || (higher == 0 && later.highAt.isBefore(highAt));
        return new PriceRange(
                market,
                pairId,
                laterLow ? later.low : low,
                laterHigh ? later.high : high,
                later.from.isBefore(from) ? later.from : from,
                false,
                source,
                laterLow ? later.lowAt : lowAt,
                laterHigh ? later.highAt : highAt);
    }
}
