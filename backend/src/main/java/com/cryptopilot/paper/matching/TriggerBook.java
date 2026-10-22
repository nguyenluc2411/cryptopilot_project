package com.cryptopilot.paper.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.matching.PaperOrderBook.BookKey;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Spot stops that wait for the last price to trigger them, per (market, pair): STOP_LOSS, STOP_LOSS_LIMIT,
 * TAKE_PROFIT and TAKE_PROFIT_LIMIT with a stopPrice, a trailing delta or both, as the exchange's Spot API has them
 * (TR-09, TR-11).
 *
 * <ul>
 *   <li>A stop without a trailing delta triggers when a price trades at or above its stopPrice ({@code AT_OR_ABOVE})
 *       or at or below it ({@code AT_OR_BELOW}), at the time that price traded.
 *   <li>A trailing stop tracks the best price: the highest for a SELL, the lowest for a BUY. With a stopPrice it starts
 *       at the first trade that meets the stop condition; without one at the first trade after the placement; that
 *       trade is the first best price. It triggers when a price comes back from the best price by the trailing delta:
 *       at or below the best less the delta for a SELL, at or above the best plus the delta for a BUY.
 *   <li>A price traded before the order was placed never starts, moves or triggers it (D-77).
 *   <li>The trades of a range are taken one by one, in the order they traded.
 *   <li>The low and the high of a candle are taken in the order they traded. A candle's low and high carry the same
 *       time (D-78), and which came first is not known: they are then taken so that the best price comes last, so a
 *       candle alone never triggers a trailing stop on a pullback that may have come before its peak.
 * </ul>
 *
 * <p>A triggered order leaves the book; the engine then executes it as the MARKET or LIMIT order it carries. The best
 * prices that moved are reported by {@link #movedExtremes()} so the engine can store them, and a restart resumes from
 * them.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns a book.
 *
 * <p>Rule: TR-09, TR-11; D-77, D-78.
 *
 * <p>Reference: Binance Spot API documentation, "Trailing Stop FAQ" (trailing delta in basis points; tracking from the
 * stop condition or the next trade; the peak for a SELL, the trough for a BUY).
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

    /**
     * A stop just watched, run over trades read before it was watched and traded after it was placed, as a range of
     * them would have run it (D-77). A triggered stop leaves the book; a best price it moved is reported as on a range.
     *
     * <p>Rule: TR-09, TR-11; D-77.
     *
     * @param trades trades after the stop's placement, as one range
     */
    public Optional<Triggered> catchUp(UUID orderId, PriceRange trades) {
        BookKey key = keys.get(orderId);
        if (key == null) {
            return Optional.empty();
        }
        Watch watch = books.get(key).get(orderId);
        Triggered fired = watch.onRange(trades);
        if (fired != null) {
            untrack(orderId);
        } else if (watch.extremeMoved) {
            watch.extremeMoved = false;
            moved.put(orderId, watch.extreme);
        }
        return Optional.ofNullable(fired);
    }

    /** The best prices of the trailing stops that moved since the last call, by order; cleared by the call. */
    public Map<UUID, BigDecimal> movedExtremes() {
        Map<UUID, BigDecimal> drained = Map.copyOf(moved);
        moved.clear();
        return drained;
    }

    /** The price a trailing stop of {@code side} triggers at, when its best price is {@code extreme}. */
    public static BigDecimal trailingStop(OrderSide side, BigDecimal extreme, BigDecimal trailingPercent) {
        BigDecimal share = trailingPercent.divide(HUNDRED, MathContext.DECIMAL64);
        BigDecimal factor = side == OrderSide.SELL ? BigDecimal.ONE.subtract(share) : BigDecimal.ONE.add(share);
        return extreme.multiply(factor, MathContext.DECIMAL64);
    }

    /**
     * An order the price triggered.
     *
     * @param order the order as it was watched
     * @param at when the price that triggered it traded
     * @param stopPrice the price it triggered at: a plain stop's stopPrice, or a trailing stop's at that moment
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
            boolean sell = order.side() == OrderSide.SELL;
            List<Point> points;
            if (range.prints().isEmpty()) {
                points = new ArrayList<>(
                        List.of(new Point(range.low(), range.lowAt()), new Point(range.high(), range.highAt())));
                // Same time: the best price last, so a pullback is never assumed to have followed it.
                points.sort(Comparator.comparing(Point::at)
                        .thenComparing(
                                point -> sell ? point.price() : point.price().negate()));
            } else {
                // Every trade in the order it traded, so one after the placement counts though an earlier set the low.
                points = range.prints().stream()
                        .map(print -> new Point(print.price(), print.at()))
                        .toList();
            }
            for (Point point : points) {
                if (point.at().isBefore(order.placedAt())) {
                    continue;
                }
                Triggered fired = order.trailing() ? trail(point, sell) : stop(point);
                if (fired != null) {
                    return fired;
                }
            }
            return null;
        }

        /** Whether the stop condition is met at {@code price}. */
        private boolean met(BigDecimal price) {
            return order.condition().isMet(price, order.stopPrice());
        }

        private Triggered stop(Point point) {
            return met(point.price()) ? new Triggered(order, point.at(), order.stopPrice()) : null;
        }

        private Triggered trail(Point point, boolean sell) {
            BigDecimal price = point.price();
            if (extreme == null) {
                // Tracking starts at the first trade that meets the stop condition, or without one at the first trade.
                if (order.stopPrice() == null || met(price)) {
                    extreme = price;
                    extremeMoved = true;
                }
                return null;
            }
            if (sell ? price.compareTo(extreme) > 0 : price.compareTo(extreme) < 0) {
                extreme = price;
                extremeMoved = true;
                return null;
            }
            BigDecimal stop = trailingStop(order.side(), extreme, order.trailingPercent());
            int side = price.compareTo(stop);
            boolean reached = sell ? side <= 0 : side >= 0;
            return reached ? new Triggered(order, point.at(), stop) : null;
        }
    }

    private record Point(BigDecimal price, Instant at) {}
}
