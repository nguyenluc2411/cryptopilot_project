package com.cryptopilot.market;

import com.cryptopilot.common.util.Rounding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The exchange's trading rules for one pair on one market: the price increment, the quantity increment
 * and the smallest order value.
 *
 * <p>A value object, compared by value and valid on construction. Each figure is positive, and none
 * carries more decimals than its column can hold — {@code numeric(28,12)} for the two increments,
 * {@code numeric(28,8)} for the minimum value — because a value the database would round silently is a
 * rule the system would then enforce differently from the exchange.
 *
 * <p>It is also where the two roundings of a plan meet their rules: a quantity is floored to the step
 * size (BR-23) and a price entered by a trader is rounded to the nearest tick (BR-30), both through
 * {@link Rounding}, so the arithmetic lives once and the rules travel with the figures they apply.
 *
 * <p>Rule: BR-23, BR-25, BR-30; TECHNICAL_DESIGN 5.4 and 7.5.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 ("Value Objects":
 * immutable, self-validating, and a natural home for the operations on the values they hold).
 *
 * @param tickSize the price increment ({@code PRICE_FILTER.tickSize})
 * @param stepSize the quantity increment ({@code LOT_SIZE.stepSize})
 * @param minNotional the smallest order value in the quote asset
 */
public record PairFilters(BigDecimal tickSize, BigDecimal stepSize, BigDecimal minNotional) {

    /** Decimals {@code numeric(28,12)} holds: the tick and step columns. */
    public static final int INCREMENT_SCALE = 12;

    /** Decimals {@code numeric(28,8)} holds: the minimum notional column. */
    public static final int NOTIONAL_SCALE = 8;

    public PairFilters {
        requirePositive(tickSize, "tickSize", INCREMENT_SCALE);
        requirePositive(stepSize, "stepSize", INCREMENT_SCALE);
        requirePositive(minNotional, "minNotional", NOTIONAL_SCALE);
    }

    /**
     * A quantity floored to the step size (BR-23). Never rounded up: a position one step larger than the
     * risk budget allows would risk more than the trader chose.
     *
     * <p>Rule: BR-23.
     */
    public BigDecimal floorQuantity(BigDecimal quantity) {
        return Rounding.toStepSize(quantity, stepSize);
    }

    /**
     * A price entered by a trader, rounded to the nearest tick, halves up (BR-30; TECHNICAL_DESIGN 5.4).
     *
     * <p>Rule: BR-30.
     */
    public BigDecimal roundPrice(BigDecimal price) {
        return Rounding.toTickSize(price, tickSize, RoundingMode.HALF_UP);
    }

    /**
     * Whether an order of this value is below the exchange's minimum and would be refused there (MSG15 in
     * TECHNICAL_DESIGN 7.5). The minimum itself is allowed.
     */
    public boolean isBelowMinNotional(BigDecimal notional) {
        Objects.requireNonNull(notional, "notional must not be null");
        return notional.compareTo(minNotional) < 0;
    }

    private static void requirePositive(BigDecimal value, String name, int maxScale) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
        if (value.stripTrailingZeros().scale() > maxScale) {
            throw new IllegalArgumentException(
                    name + " has more than " + maxScale + " decimals, which its column would round: " + value);
        }
    }
}
