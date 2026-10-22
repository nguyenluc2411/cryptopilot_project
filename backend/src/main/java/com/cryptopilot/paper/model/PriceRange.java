package com.cryptopilot.paper.model;

import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.FillSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The prices a pair has traded over a stretch of time, as the paper matching engine reads them: the low and high of a
 * 1-minute candle, live from the stream or replayed from a closed candle (ADR-011), or of one or more market trades
 * read together (TR-05).
 *
 * <p>A candle's low and high are recorded at its open time (D-78). A trade's price is recorded at the time it was
 * traded, so the trades merged into one range keep the time of the trade at their low and of the one at their high:
 * a BUY the range reached is filled at the low's time, a SELL at the high's. Trades also keep how much takers sold and
 * bought at each price, which moves the queue of the orders waiting there (Q-T8); a candle carries no such volume.
 *
 * <p>The trades merged into one range are also kept one by one, in the order they traded ({@link #prints()}), so an
 * order placed between two of them is reached by the later one alone, and only the volume traded after its placement
 * moves its queue (D-77). The low and the high alone, each with the time of its earliest trade, would lose a later
 * trade at a price an earlier one already set.
 *
 * <p>Rule: TR-02, TR-05; NSF-07; Q-T6, Q-T8; D-77, D-78; ADR-011.
 *
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (each element is judged
 * by its own event time, not by the time of the batch it was read in).
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
 * @param soldAt the base quantity takers sold at each price: it hit the bids there, ahead of a BUY waiting at it
 * @param boughtAt the base quantity takers bought at each price: it lifted the asks there, ahead of a SELL waiting at it
 * @param open a replayed candle's first price, which a market stop it triggers executes at when it opened past the
 *     stop; {@code null} otherwise
 * @param prints the trades of the range one by one, earliest first; empty for a candle, whose trades are not known
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
        Instant highAt,
        NavigableMap<BigDecimal, BigDecimal> soldAt,
        NavigableMap<BigDecimal, BigDecimal> boughtAt,
        BigDecimal open,
        List<Print> prints) {

    public PriceRange {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lowAt, "lowAt");
        Objects.requireNonNull(highAt, "highAt");
        // Keyed by value, so 80000 and 80000.00 are one price.
        soldAt = Collections.unmodifiableNavigableMap(new TreeMap<>(soldAt));
        boughtAt = Collections.unmodifiableNavigableMap(new TreeMap<>(boughtAt));
        prints = List.copyOf(prints);
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low " + low + " is above high " + high);
        }
    }

    /** A range without a candle's open. */
    public PriceRange(
            MarketType market,
            UUID pairId,
            BigDecimal low,
            BigDecimal high,
            Instant from,
            boolean closed,
            FillSource source,
            Instant lowAt,
            Instant highAt,
            NavigableMap<BigDecimal, BigDecimal> soldAt,
            NavigableMap<BigDecimal, BigDecimal> boughtAt) {
        this(market, pairId, low, high, from, closed, source, lowAt, highAt, soldAt, boughtAt, null, List.of());
    }

    /** A 1-minute candle's range: its low and high both recorded at its open time (D-78), with no volume by price. */
    public PriceRange(
            MarketType market,
            UUID pairId,
            BigDecimal low,
            BigDecimal high,
            Instant from,
            boolean closed,
            FillSource source) {
        this(market, pairId, low, high, from, closed, source, null);
    }

    /** A 1-minute candle's range with its first price. */
    public PriceRange(
            MarketType market,
            UUID pairId,
            BigDecimal low,
            BigDecimal high,
            Instant from,
            boolean closed,
            FillSource source,
            BigDecimal open) {
        this(
                market,
                pairId,
                low,
                high,
                from,
                closed,
                source,
                from,
                from,
                new TreeMap<>(),
                new TreeMap<>(),
                open,
                List.of());
    }

    /** One live market trade, at its price and its trade time, with its quantity on the side its taker took. */
    public static PriceRange of(MarketTrade trade) {
        return ofPrints(
                trade.market(),
                trade.pairId(),
                List.of(new Print(trade.price(), trade.quantity(), trade.buyerMaker(), trade.tradeTime())));
    }

    /** Live trades of one pair, at least one, earliest first, read as one range. */
    public static PriceRange ofPrints(MarketType market, UUID pairId, List<Print> prints) {
        if (prints.isEmpty()) {
            throw new IllegalArgumentException("a range of trades holds at least one");
        }
        Print first = prints.getFirst();
        Print low = first;
        Print high = first;
        NavigableMap<BigDecimal, BigDecimal> sold = new TreeMap<>();
        NavigableMap<BigDecimal, BigDecimal> bought = new TreeMap<>();
        for (Print print : prints) {
            // Strictly lower or higher: the earliest trade at the low or the high keeps its time.
            if (print.price().compareTo(low.price()) < 0) {
                low = print;
            }
            if (print.price().compareTo(high.price()) > 0) {
                high = print;
            }
            (print.takerSold() ? sold : bought).merge(print.price(), print.quantity(), BigDecimal::add);
        }
        return new PriceRange(
                market,
                pairId,
                low.price(),
                high.price(),
                first.at(),
                false,
                FillSource.LIVE,
                low.at(),
                high.at(),
                sold,
                bought,
                null,
                prints);
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
                market,
                pairId,
                low.min(later.low),
                high.max(later.high),
                from,
                closed || later.closed,
                source,
                open != null ? open : later.open);
    }

    /**
     * These trades followed by later ones of the same pair, read as one range: the lowest low and the highest high,
     * each with the time of the earliest trade at it, the volume sold and bought at each price added up, and every
     * trade kept in the order it traded.
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
                laterHigh ? later.highAt : highAt,
                added(soldAt, later.soldAt),
                added(boughtAt, later.boughtAt),
                null,
                inTradeOrder(prints, later.prints));
    }

    /** Two runs of trades, each earliest first, as one; at an equal time a trade of the first run comes first. */
    private static List<Print> inTradeOrder(List<Print> earlier, List<Print> later) {
        List<Print> merged = new ArrayList<>(earlier.size() + later.size());
        int i = 0;
        int j = 0;
        while (i < earlier.size() || j < later.size()) {
            boolean takeLater = i == earlier.size()
                    || (j < later.size()
                            && later.get(j).at().isBefore(earlier.get(i).at()));
            merged.add(takeLater ? later.get(j++) : earlier.get(i++));
        }
        return merged;
    }

    private static NavigableMap<BigDecimal, BigDecimal> added(
            NavigableMap<BigDecimal, BigDecimal> earlier, NavigableMap<BigDecimal, BigDecimal> later) {
        NavigableMap<BigDecimal, BigDecimal> sum = new TreeMap<>(earlier);
        later.forEach((price, quantity) -> sum.merge(price, quantity, BigDecimal::add));
        return sum;
    }

    /**
     * One market trade of a range.
     *
     * @param price the price it traded at
     * @param quantity the base quantity
     * @param takerSold whether its taker sold, hitting the bids ({@code buyerMaker}); otherwise the taker bought
     * @param at its trade time
     */
    public record Print(BigDecimal price, BigDecimal quantity, boolean takerSold, Instant at) {

        public Print {
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(at, "at");
        }
    }
}
