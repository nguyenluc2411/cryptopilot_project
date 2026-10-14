package com.cryptopilot.paper.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.matching.PaperOrderBook.BookKey;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Spot orders that wait for the last price to trigger them, per (market, pair): stop limits (TR-09) and trailing
 * stops (TR-11).
 *
 * <ul>
 *   <li>A stop limit triggers when a price trades at or above its stop ({@code AT_OR_ABOVE}) or at or below it
 *       ({@code AT_OR_BELOW}), at the time that price traded.
 *   <li>A trailing stop is active from its placement, or once a price reaches its activation price (at or above it
 *       for one that triggers below, at or below for one that triggers above). Active, it follows the best price: the
 *       highest for one that triggers below, the lowest for one that triggers above. Its stop is that best price less
 *       (or plus) its callback rate, and it triggers when a price trades at or past that stop.
 *   <li>A price traded before the order was placed never activates, moves or triggers it (D-77).
 *   <li>The low and the high of a range are taken in the order they traded. A candle's low and high carry the same
 *       time (D-78), and which came first is not known: they are then taken so that the best price comes last, so a
 *       candle alone never triggers a trailing stop on a pullback that may have come before its peak.
 * </ul>
 *
 * <p>A triggered order leaves the book; the engine then places the limit order it carries. The best prices that moved
 * are reported by {@link #movedExtremes()} so the engine can store them, and a restart resumes from them.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns a book.
 *
 * <p>Rule: TR-09, TR-11; D-77, D-78.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (stop orders become active orders when the price reaches the stop).
 */
public final class TriggerBook {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Map<BookKey, Map<UUID, Watch>> books = new HashMap<>();
    private final Map<UUID, BookKey> keys = new HashMap<>();
    private final Map<UUID, BigDecimal> moved = new LinkedHashMap<>();

    /** Starts watching an order, replacing an earlier copy of it. */
    public void track(TriggerOrder order) {
        untrack(order.orderId());
        BookKey key = new BookKey(order.market(), order.pairId());
        books.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(order.orderId(), new Watch(order));
        keys.put(order.orderId(), key);
    }

    /** Stops watching an order; an unknown one is ignored. */
    public void untrack(UUID orderId) {
        BookKey key = keys.remove(orderId);
        if (key == null) {
            return;
        }
        Map<UUID, Watch> pair = books.get(key);
        pair.remove(orderId);
        if (pair.isEmpty()) {
            books.remove(key);
        }
        moved.remove(orderId);
    }

    /** Whether any order of the pair waits for a trigger. */
    public boolean hasOrders(MarketType market, UUID pairId) {
        return books.containsKey(new BookKey(market, pairId));
    }

    /** How many orders are watched. */
    public int size() {
        return keys.size();
    }

    /** The orders the range triggered, which leave the book, earliest trigger first, then earliest placed. */
    public List<Triggered> onRange(PriceRange range) {
        BookKey key = new BookKey(range.market(), range.pairId());
        Map<UUID, Watch> pair = books.get(key);
        if (pair == null) {
            return List.of();
        }
        List<Triggered> triggered = new ArrayList<>();
        for (Watch watch : List.copyOf(pair.values())) {
            Triggered fired = watch.onRange(range);
            if (fired != null) {
                untrack(watch.order.orderId());
                triggered.add(fired);
            } else if (watch.extremeMoved) {
                watch.extremeMoved = false;
                moved.put(watch.order.orderId(), watch.extreme);
            }
        }
        triggered.sort(Comparator.comparing(Triggered::at)
                .thenComparing(fired -> fired.order().placedAt())
                .thenComparing(fired -> fired.order().orderId()));
        return triggered;
    }

    /** The best prices of the trailing stops that moved since the last call, by order; cleared by the call. */
    public Map<UUID, BigDecimal> movedExtremes() {
        Map<UUID, BigDecimal> drained = Map.copyOf(moved);
        moved.clear();
        return drained;
    }

    /** The stop of a trailing stop whose best price is {@code extreme}. */
    public static BigDecimal trailingStop(TriggerCondition condition, BigDecimal extreme, BigDecimal callbackRate) {
        BigDecimal share = callbackRate.divide(HUNDRED, MathContext.DECIMAL64);
        BigDecimal factor =
                condition == TriggerCondition.AT_OR_BELOW ? BigDecimal.ONE.subtract(share) : BigDecimal.ONE.add(share);
        return extreme.multiply(factor, MathContext.DECIMAL64);
    }

    /**
     * An order the price triggered.
     *
     * @param order the order as it was watched
     * @param at when the price that triggered it traded
     * @param stopPrice the stop it triggered at: a stop limit's own, or a trailing stop's at that moment
     */
    public record Triggered(TriggerOrder order, Instant at, BigDecimal stopPrice) {}

    /** One watched order and, for a trailing stop, where it stands. */
    private static final class Watch {

        private final TriggerOrder order;
        private BigDecimal extreme;
        private boolean extremeMoved;

        Watch(TriggerOrder order) {
            this.order = order;
            this.extreme = order.extreme();
        }

        Triggered onRange(PriceRange range) {
            boolean below = order.condition() == TriggerCondition.AT_OR_BELOW;
            List<Point> points = new ArrayList<>(
                    List.of(new Point(range.low(), range.lowAt()), new Point(range.high(), range.highAt())));
            // Same time: the best price last, so a pullback is never assumed to have followed it.
            points.sort(Comparator.comparing(Point::at)
                    .thenComparing(
                            point -> below ? point.price() : point.price().negate()));
            for (Point point : points) {
                if (point.at().isBefore(order.placedAt())) {
                    continue;
                }
                Triggered fired = order.trailing() ? trail(point, below) : stop(point, below);
                if (fired != null) {
                    return fired;
                }
            }
            return null;
        }

        private Triggered stop(Point point, boolean below) {
            int side = point.price().compareTo(order.stopPrice());
            boolean reached = below ? side <= 0 : side >= 0;
            return reached ? new Triggered(order, point.at(), order.stopPrice()) : null;
        }

        private Triggered trail(Point point, boolean below) {
            BigDecimal price = point.price();
            if (extreme == null) {
                int side = price.compareTo(order.activationPrice());
                // One that triggers below is activated as the price rises to its activation price, and the reverse.
                boolean activated = below ? side >= 0 : side <= 0;
                if (!activated) {
                    return null;
                }
                extreme = price;
                extremeMoved = true;
                return null;
            }
            if (below ? price.compareTo(extreme) > 0 : price.compareTo(extreme) < 0) {
                extreme = price;
                extremeMoved = true;
                return null;
            }
            BigDecimal stop = trailingStop(order.condition(), extreme, order.callbackRate());
            int side = price.compareTo(stop);
            boolean reached = below ? side <= 0 : side >= 0;
            return reached ? new Triggered(order, point.at(), stop) : null;
        }
    }

    private record Point(BigDecimal price, Instant at) {}
}
