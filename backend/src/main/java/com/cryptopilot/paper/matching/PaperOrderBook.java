package com.cryptopilot.paper.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.LimitFillMode;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The working LIMIT orders of every pair, waiting at their prices: per (market, pair), BUY orders below the price and
 * SELL orders above it.
 *
 * <ul>
 *   <li>A BUY order is reached when the price trades below its own, a SELL order above it. At exactly its price it
 *       depends on the {@link LimitFillMode} (Q-T8): with {@code QUEUE}, as Binance matches, once the takers selling
 *       there (for a BUY) have consumed the quantity that was ahead of it, or as soon as a taker buys there; with
 *       {@code TOUCH} at once; with {@code CROSS} never. Reached, it fills at its own price, also when the price
 *       traded through it (a gap).
 *   <li>The quantity ahead of an order is what the exchange's book showed at its price when the order was tracked, or,
 *       when that was not known (outside the depth, or rebuilt at a restart), what it shows when a trade first reaches
 *       that price. The paper orders do not queue behind each other: none of them is in the exchange's book.
 *   <li>A price traded before the order was placed never fills it (D-77): a trade before its placement, or a candle
 *       that opened before it, whose low and high may hold prices traded before the order existed. An order whose
 *       price the market already stood at when it was placed has executed then, as a taker, and never waits here.
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
 * <p>Rule: TR-02, TR-05; NSF-07; Q-T8; D-77, D-78.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it) and ch. 6 (price
 * priority, then time precedence).
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

    /** Knows nothing of the exchange's book: an order's queue is empty when first reached. */
    private static final QueueLookup NO_DEPTH = (market, pairId, side, price) -> Optional.empty();

    private final LimitFillMode mode;
    private final Map<BookKey, Sides> books = new HashMap<>();
    private final Map<UUID, RestingOrder> tracked = new HashMap<>();

    /** The quantity still ahead of an order at its price ({@code QUEUE}); absent while not known. */
    private final Map<UUID, BigDecimal> ahead = new HashMap<>();

    /** A book whose orders are reached as {@code mode} says. */
    public PaperOrderBook(LimitFillMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Starts tracking an order, replacing an earlier copy of it, with the quantity ahead of it not known. */
    public void track(RestingOrder order) {
        track(order, null);
    }

    /**
     * Starts tracking an order, replacing an earlier copy of it.
     *
     * @param queueAhead the quantity the exchange's book showed at its price, ahead of it; {@code null} when not known
     */
    public void track(RestingOrder order, BigDecimal queueAhead) {
        untrack(order.orderId());
        books.computeIfAbsent(new BookKey(order.market(), order.pairId()), ignored -> new Sides())
                .of(order.side())
                .add(order.limitPrice(), order.orderId());
        tracked.put(order.orderId(), order);
        if (queueAhead != null) {
            ahead.put(order.orderId(), queueAhead);
        }
    }

    /** Stops tracking an order; an unknown one is ignored. */
    public void untrack(UUID orderId) {
        RestingOrder order = tracked.remove(orderId);
        ahead.remove(orderId);
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

    /** The quantity still ahead of an order at its price, when known; for tests. */
    Optional<BigDecimal> queueAhead(UUID orderId) {
        return Optional.ofNullable(ahead.get(orderId));
    }

    /** {@link #onRange(PriceRange, QueueLookup)} knowing nothing of the exchange's book. */
    public List<RestingOrder> onRange(PriceRange range) {
        return onRange(range, NO_DEPTH);
    }

    /** The orders {@link #reach(PriceRange, QueueLookup)} returns, without the time each was reached. */
    public List<RestingOrder> onRange(PriceRange range, QueueLookup depth) {
        return reach(range, depth).stream().map(Reached::order).toList();
    }

    /**
     * The orders the range reached, which leave the book, each with the time it was reached, in price-time priority.
     *
     * <ul>
     *   <li>A range of trades reaches each order by its own trades: the first one at or after its placement at a
     *       price the {@link LimitFillMode} says reaches it, and only those trades move its queue (D-77).
     *   <li>A candle reaches BUY orders above its low and SELL orders below its high, and those at it as the mode
     *       says, at its open time; one that opened before an order was placed never reaches it (D-77, D-78).
     * </ul>
     *
     * @param depth the exchange's book now, read for the queue of an order whose queue is not known yet
     */
    public List<Reached> reach(PriceRange range, QueueLookup depth) {
        BookKey key = new BookKey(range.market(), range.pairId());
        Sides pair = books.get(key);
        if (pair == null) {
            return List.of();
        }
        List<Reached> reached = range.prints().isEmpty()
                ? reachedByBounds(key, pair, range, depth)
                : reachedByTrades(key, pair, range, depth);
        if (pair.isEmpty()) {
            books.remove(key);
        }
        reached.sort(Comparator.comparing(Reached::order, PRIORITY));
        return reached;
    }

    /**
     * An order just tracked, matched against trades read before it was tracked: those traded after it was placed, as
     * a range of them would have reached it. Their volume at its price does not move its queue, which was read from
     * the exchange's book after they traded. A reached order leaves the book.
     *
     * <p>Rule: TR-05; Q-T8; D-77.
     *
     * @param prints trades after the order's placement, earliest first
     */
    public Optional<Reached> catchUp(UUID orderId, List<PriceRange.Print> prints) {
        RestingOrder order = tracked.get(orderId);
        if (order == null) {
            return Optional.empty();
        }
        BookKey key = new BookKey(order.market(), order.pairId());
        Instant at = reachedAt(key, order, prints, NO_DEPTH, false);
        if (at == null) {
            return Optional.empty();
        }
        untrack(orderId);
        return Optional.of(new Reached(order, at));
    }

    /** Each order a range of trades may reach, judged by the trades at or after its placement. */
    private List<Reached> reachedByTrades(BookKey key, Sides pair, PriceRange range, QueueLookup depth) {
        List<Reached> reached = new ArrayList<>();
        List<UUID> candidates = new ArrayList<>(pair.buys.idsFrom(range.low()));
        candidates.addAll(pair.sells.idsUpTo(range.high()));
        for (UUID id : candidates) {
            RestingOrder order = tracked.get(id);
            Instant at = reachedAt(key, order, range.prints(), depth, true);
            if (at != null) {
                pair.of(order.side()).remove(order.limitPrice(), id);
                tracked.remove(id);
                ahead.remove(id);
                reached.add(new Reached(order, at));
            }
        }
        return reached;
    }

    /**
     * When the first of {@code prints} at or after the order's placement reached it: a price through its own, or at
     * it as the {@link LimitFillMode} says; {@code null} when none did. With {@code QUEUE}, the takers of its own side
     * at its price consume what is ahead of it, in the order they traded, when {@code consumeQueue}.
     */
    private Instant reachedAt(
            BookKey key, RestingOrder order, List<PriceRange.Print> prints, QueueLookup depth, boolean consumeQueue) {
        boolean buy = order.side() == OrderSide.BUY;
        BigDecimal price = order.limitPrice();
        for (PriceRange.Print print : prints) {
            if (print.at().isBefore(order.placedAt())) {
                continue;
            }
            int compared = print.price().compareTo(price);
            int through = buy ? -compared : compared;
            if (through > 0 || (through == 0 && mode == LimitFillMode.TOUCH)) {
                return print.at();
            }
            if (through < 0 || mode != LimitFillMode.QUEUE) {
                continue;
            }
            if (print.takerSold() != buy) {
                // A taker of the other side traded at this price: no exchange order of this side was left at it.
                return print.at();
            }
            if (!consumeQueue) {
                continue;
            }
            BigDecimal before = ahead.computeIfAbsent(
                    order.orderId(), ignored -> depth.quantityAt(key.market(), key.pairId(), order.side(), price)
                            .orElse(BigDecimal.ZERO));
            if (print.quantity().compareTo(before) > 0) {
                return print.at();
            }
            ahead.put(order.orderId(), before.subtract(print.quantity()));
        }
        return null;
    }

    /** The orders a candle reached, at its open time: see {@link #reach}. */
    private List<Reached> reachedByBounds(BookKey key, Sides pair, PriceRange range, QueueLookup depth) {
        boolean touch = mode == LimitFillMode.TOUCH;
        List<UUID> ids = new ArrayList<>(pair.buys.fireAbove(range.low(), touch));
        ids.addAll(pair.sells.fireBelow(range.high(), touch));
        if (mode == LimitFillMode.QUEUE) {
            ids.addAll(atQueue(key, pair.buys, OrderSide.BUY, range.low(), range.lowAt(), range, depth));
            ids.addAll(atQueue(key, pair.sells, OrderSide.SELL, range.high(), range.highAt(), range, depth));
        }
        List<Reached> reached = new ArrayList<>();
        for (UUID id : ids) {
            RestingOrder order = tracked.get(id);
            Instant tradedAt = order.side() == OrderSide.BUY ? range.lowAt() : range.highAt();
            if (tradedAt.isBefore(order.placedAt())) {
                pair.of(order.side()).add(order.limitPrice(), id);
            } else {
                tracked.remove(id);
                ahead.remove(id);
                reached.add(new Reached(order, tradedAt));
            }
        }
        return reached;
    }

    /**
     * The orders waiting at exactly {@code price}, the range's low for BUY orders or its high for SELL orders, that
     * the trades there reached: all of them when a taker of the other side traded there, otherwise each one whose
     * queue the takers of its own side consumed; the others keep what is still ahead of them. Removed from the level.
     */
    private List<UUID> atQueue(
            BookKey key,
            Levels levels,
            OrderSide side,
            BigDecimal price,
            Instant tradedAt,
            PriceRange range,
            QueueLookup depth) {
        Set<UUID> waiting = levels.at(price);
        if (waiting == null) {
            return List.of();
        }
        boolean buy = side == OrderSide.BUY;
        if ((buy ? range.boughtAt() : range.soldAt()).containsKey(price)) {
            // A taker of the other side traded at this price: no exchange order of this side was left at it.
            return levels.fireAt(price);
        }
        BigDecimal traded = (buy ? range.soldAt() : range.boughtAt()).get(price);
        if (traded == null) {
            return List.of();
        }
        List<UUID> fired = new ArrayList<>();
        for (UUID id : List.copyOf(waiting)) {
            if (tradedAt.isBefore(tracked.get(id).placedAt())) {
                continue;
            }
            BigDecimal before =
                    ahead.computeIfAbsent(id, ignored -> depth.quantityAt(key.market(), key.pairId(), side, price)
                            .orElse(BigDecimal.ZERO));
            if (traded.compareTo(before) > 0) {
                levels.remove(price, id);
                fired.add(id);
            } else {
                ahead.put(id, before.subtract(traded));
            }
        }
        return fired;
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

    /**
     * An order a range reached.
     *
     * @param order the order as it was tracked
     * @param at when the price that reached it traded: a trade's time, or a candle's open time
     */
    public record Reached(RestingOrder order, Instant at) {}

    /** The quantity the exchange's book shows now at a price, on the side an order of {@code side} would wait. */
    @FunctionalInterface
    public interface QueueLookup {

        /** The quantity, zero when the price is inside the known depth with nothing at it; empty when not known. */
        Optional<BigDecimal> quantityAt(MarketType market, UUID pairId, OrderSide side, BigDecimal price);
    }

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

        /** The ids at levels at or above {@code low}: the buys a price down to it may reach. */
        List<UUID> idsFrom(BigDecimal low) {
            return ids(levels.tailMap(low, true));
        }

        /** The ids at levels at or below {@code high}: the sells a price up to it may reach. */
        List<UUID> idsUpTo(BigDecimal high) {
            return ids(levels.headMap(high, true));
        }

        private static List<UUID> ids(NavigableMap<BigDecimal, Set<UUID>> range) {
            List<UUID> ids = new ArrayList<>();
            range.values().forEach(ids::addAll);
            return ids;
        }

        /** The ids waiting at exactly {@code level}, or {@code null}. */
        Set<UUID> at(BigDecimal level) {
            return levels.get(level);
        }

        /** Removes and returns the ids at levels above {@code low}, or at it too: the buys a price down to it reached. */
        List<UUID> fireAbove(BigDecimal low, boolean inclusive) {
            return fire(levels.tailMap(low, inclusive));
        }

        /** Removes and returns the ids at levels below {@code high}, or at it too: the sells a price up to it reached. */
        List<UUID> fireBelow(BigDecimal high, boolean inclusive) {
            return fire(levels.headMap(high, inclusive));
        }

        /** Removes and returns the ids at exactly {@code level}. */
        List<UUID> fireAt(BigDecimal level) {
            return fire(levels.subMap(level, true, level, true));
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
