package com.cryptopilot.paper.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The working LIMIT orders of every pair, waiting at their prices: per (market, pair), BUY orders below the price and
 * SELL orders above it.
 *
 * <ul>
 *   <li>A BUY order is reached when a candle trades at or below its price, a SELL order at or above it; both bounds
 *       count. Reached, it fills at its own price, also when the candle traded through it (a gap).
 *   <li>A candle that opened before the order was placed never fills it (D-77): its low and high may hold prices
 *       traded before the order existed. An order whose price the market already stood at when it was placed has
 *       executed then, as a taker, and never waits here.
 * </ul>
 *
 * <p>The orders a range reaches are returned in price-time priority (see {@link #PRIORITY}), so when many Traders
 * wait at the same price the one who placed first is filled first.
 *
 * <p>Whether a fill is stored is decided by the order's status under the Trader's lock, not here: an order cancelled
 * meanwhile is simply not filled.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns a book, so it needs no locks and sees the updates of a pair
 * in arrival order.
 *
 * <p>Rule: TR-02; NSF-07; D-77, D-78.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it).
 * <p>Reference: Cormen, T. H. et al. (2009). <i>Introduction to Algorithms</i> (3rd ed.). MIT Press, ch. 13
 * (red-black trees: O(log n) insert, delete and range search).
 */
public final class PaperOrderBook {

    /**
     * Price-time priority, as Binance matches: of the orders one range reached, a BUY at a higher price and a SELL at
     * a lower price come first, and among equal prices the order placed first; the id breaks a tie of instants.
     */
    private static final Comparator<RestingOrder> PRIORITY = Comparator.comparing(RestingOrder::side)
            .thenComparing((a, b) -> a.side() == OrderSide.BUY
                    ? b.limitPrice().compareTo(a.limitPrice())
                    : a.limitPrice().compareTo(b.limitPrice()))
            .thenComparing(RestingOrder::placedAt)
            .thenComparing(RestingOrder::orderId);

    private final Map<BookKey, Sides> books = new HashMap<>();
    private final Map<UUID, RestingOrder> tracked = new HashMap<>();

    /** Starts tracking an order, replacing an earlier copy of it. */
    public void track(RestingOrder order) {
        untrack(order.orderId());
        books.computeIfAbsent(new BookKey(order.market(), order.pairId()), ignored -> new Sides())
                .of(order.side())
                .add(order.limitPrice(), order.orderId());
        tracked.put(order.orderId(), order);
    }

    /** Stops tracking an order; an unknown one is ignored. */
    public void untrack(UUID orderId) {
        RestingOrder order = tracked.remove(orderId);
        if (order == null) {
            return;
        }
        BookKey key = new BookKey(order.market(), order.pairId());
        Sides pair = books.get(key);
        pair.of(order.side()).remove(order.limitPrice(), orderId);
        if (pair.isEmpty()) {
            books.remove(key);
        }
    }

    /** Whether any order of the pair waits. */
    public boolean hasOrders(MarketType market, UUID pairId) {
        return books.containsKey(new BookKey(market, pairId));
    }

    /**
     * The orders the range reached, which leave the book: BUY orders at or above its low, SELL orders at or below its
     * high. An order placed after the range's candle opened stays (D-77).
     */
    public List<RestingOrder> onRange(PriceRange range) {
        BookKey key = new BookKey(range.market(), range.pairId());
        Sides pair = books.get(key);
        if (pair == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(pair.buys.fireAtOrAbove(range.low()));
        ids.addAll(pair.sells.fireAtOrBelow(range.high()));
        List<RestingOrder> reached = new ArrayList<>();
        for (UUID id : ids) {
            RestingOrder order = tracked.get(id);
            if (range.from().isBefore(order.placedAt())) {
                pair.of(order.side()).add(order.limitPrice(), id);
            } else {
                tracked.remove(id);
                reached.add(order);
            }
        }
        if (pair.isEmpty()) {
            books.remove(key);
        }
        reached.sort(PRIORITY);
        return reached;
    }

    /** How many orders are tracked. */
    public int size() {
        return tracked.size();
    }

    /**
     * A pair of a market.
     *
     * @param market the market
     * @param pairId the pair
     */
    public record BookKey(MarketType market, UUID pairId) {}

    private static final class Sides {

        private final Levels buys = new Levels();
        private final Levels sells = new Levels();

        Levels of(OrderSide side) {
            return side == OrderSide.BUY ? buys : sells;
        }

        boolean isEmpty() {
            return buys.isEmpty() && sells.isEmpty();
        }
    }

    /** Order ids by price level. */
    private static final class Levels {

        private final TreeMap<BigDecimal, Set<UUID>> levels = new TreeMap<>();

        void add(BigDecimal level, UUID id) {
            levels.computeIfAbsent(level, ignored -> new HashSet<>()).add(id);
        }

        void remove(BigDecimal level, UUID id) {
            Set<UUID> ids = levels.get(level);
            if (ids != null && ids.remove(id) && ids.isEmpty()) {
                levels.remove(level);
            }
        }

        /** Removes and returns the ids at levels at or above {@code low}: the buys a price down to it reached. */
        List<UUID> fireAtOrAbove(BigDecimal low) {
            return fire(levels.tailMap(low, true));
        }

        /** Removes and returns the ids at levels at or below {@code high}: the sells a price up to it reached. */
        List<UUID> fireAtOrBelow(BigDecimal high) {
            return fire(levels.headMap(high, true));
        }

        boolean isEmpty() {
            return levels.isEmpty();
        }

        private static List<UUID> fire(NavigableMap<BigDecimal, Set<UUID>> reached) {
            List<UUID> fired = new ArrayList<>();
            reached.values().forEach(fired::addAll);
            reached.clear();
            return fired;
        }
    }
}
