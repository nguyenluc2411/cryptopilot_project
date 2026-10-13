package com.cryptopilot.paper.matching;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an order that takes liquidity on arrival executes against: the levels of the other side of the exchange's book,
 * best first, as Binance matches a MARKET order or a LIMIT order that crosses the book (Q-T7, plan section 11.2).
 *
 * <ul>
 *   <li>Each level taken is one execution at the level's price, so the order's average price is the VWAP of what it
 *       took, and the trade history lists one fill per level as the exchange does.
 *   <li>A LIMIT order takes no level beyond its price; what is left of it waits, expires or makes it refuse (FOK).
 *   <li>The book is only the top levels the stream sends (depth20). When the order needs more than all of them hold,
 *       the rest executes at the last one's price and the result says so ({@link Result#pastBook()}); a LIMIT order
 *       does so only when its price lies beyond that last level, so the levels it may still take are not shown.
 *   <li>The paper orders take nothing from the exchange's book: two orders in a row see the same levels.
 * </ul>
 *
 * <p>Rule: TR-06; Q-T7; plan section 11.2.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a market order walks the book, a marketable limit order stops at its price).
 */
public final class BookSweep {

    private BookSweep() {}

    /**
     * Takes {@code quantity} from {@code levels}.
     *
     * @param levels the other side of the book, best first: the asks for a BUY, the bids for a SELL; not empty
     * @param side the side of the order taking them
     * @param quantity what the order asks for, on the pair's step
     * @param limit the LIMIT order's price, or {@code null} for a MARKET order
     */
    public static Result ofQuantity(List<PriceLevel> levels, OrderSide side, BigDecimal quantity, BigDecimal limit) {
        requireLevels(levels);
        Objects.requireNonNull(side, "side");
        BigDecimal left = Objects.requireNonNull(quantity, "quantity");
        List<Slice> slices = new ArrayList<>();
        for (PriceLevel level : levels) {
            if (left.signum() == 0) {
                return new Result(slices, false);
            }
            if (limit != null && !reaches(side, level.price(), limit)) {
                return new Result(slices, false);
            }
            BigDecimal taken = left.min(level.quantity());
            if (taken.signum() > 0) {
                slices.add(new Slice(level.price(), taken));
                left = left.subtract(taken);
            }
        }
        if (left.signum() == 0) {
            return new Result(slices, false);
        }
        slices.add(new Slice(levels.getLast().price(), left));
        return new Result(merged(slices), true);
    }

    /**
     * Spends up to {@code quote} on {@code levels}, a MARKET buy sized by what it spends: at each level as much as the
     * rest of the amount buys on the pair's step, so a little of the amount may stay unspent, as on the exchange.
     *
     * @param asks the asks, best first; not empty
     * @param quote what the order spends, in the quote asset
     * @param filters the pair's filters, whose step each quantity is floored to
     */
    public static Result ofQuote(List<PriceLevel> asks, BigDecimal quote, PairFilters filters) {
        requireLevels(asks);
        Objects.requireNonNull(filters, "filters");
        BigDecimal left = Objects.requireNonNull(quote, "quote");
        List<Slice> slices = new ArrayList<>();
        for (PriceLevel level : asks) {
            BigDecimal affordable = affordable(left, level.price(), filters);
            if (affordable.signum() == 0) {
                // The levels after it cost more, so nothing more is affordable.
                return new Result(slices, false);
            }
            BigDecimal taken = affordable.min(level.quantity());
            if (taken.signum() == 0) {
                continue;
            }
            slices.add(new Slice(level.price(), taken));
            left = left.subtract(PaperOrder.buyCost(level.price(), taken));
            if (taken.compareTo(level.quantity()) < 0) {
                return new Result(slices, false);
            }
        }
        BigDecimal last = asks.getLast().price();
        BigDecimal rest = affordable(left, last, filters);
        if (rest.signum() == 0) {
            return new Result(slices, false);
        }
        slices.add(new Slice(last, rest));
        return new Result(merged(slices), true);
    }

    /** Whether a price reaches a limit: at or below it for a buy, at or above it for a sell. */
    public static boolean reaches(OrderSide side, BigDecimal price, BigDecimal limit) {
        int compared = price.compareTo(limit);
        return side == OrderSide.BUY ? compared <= 0 : compared >= 0;
    }

    /** What {@code quote} buys at {@code price}, floored to the step and to the decimals a balance holds. */
    private static BigDecimal affordable(BigDecimal quote, BigDecimal price, PairFilters filters) {
        if (quote.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return filters.floorQuantity(Rounding.divide(quote, price)).setScale(Rounding.AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /** The rest past the book executes at the last level's price, so it joins that level's slice when there is one. */
    private static List<Slice> merged(List<Slice> slices) {
        int size = slices.size();
        if (size < 2 || slices.get(size - 2).price().compareTo(slices.getLast().price()) != 0) {
            return slices;
        }
        List<Slice> joined = new ArrayList<>(slices.subList(0, size - 2));
        joined.add(new Slice(
                slices.getLast().price(),
                slices.get(size - 2).quantity().add(slices.getLast().quantity())));
        return joined;
    }

    private static void requireLevels(List<PriceLevel> levels) {
        if (Objects.requireNonNull(levels, "levels").isEmpty()) {
            throw new IllegalArgumentException("a sweep needs at least one level");
        }
    }

    /**
     * One execution: a quantity at a level's price.
     *
     * @param price the level's price
     * @param quantity the base quantity taken there
     */
    public record Slice(BigDecimal price, BigDecimal quantity) {

        public Slice {
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(quantity, "quantity");
        }
    }

    /**
     * What a sweep takes.
     *
     * @param slices the executions, best price first; empty when nothing could be taken
     * @param pastBook whether the order needed more than the levels shown, the rest taken at the last one's price
     */
    public record Result(List<Slice> slices, boolean pastBook) {

        public Result {
            slices = List.copyOf(slices);
        }

        /** The base quantity of every slice. */
        public BigDecimal quantity() {
            return slices.stream().map(Slice::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The value of every slice, unrounded. */
        public BigDecimal notional() {
            return slices.stream()
                    .map(slice -> slice.price().multiply(slice.quantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public boolean isEmpty() {
            return slices.isEmpty();
        }
    }
}
