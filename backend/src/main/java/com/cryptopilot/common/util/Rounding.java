package com.cryptopilot.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The single rounding policy for money, prices and quantities.
 *
 * <p>Every method names its {@link RoundingMode} instead of relying on a default, because the
 * direction of a rounding is a business decision rather than a formatting detail: a quantity
 * rounded up would exceed the risk budget, and a liquidation price rounded the friendly way would
 * understate the danger of a position. {@code BigDecimal} is used throughout, since binary floating
 * point cannot represent decimal prices exactly and the drift becomes visible once fees and funding
 * accumulate.
 *
 * <p>Rule: ADR-008 and TECHNICAL_DESIGN section 5.4 (numeric precision and rounding).
 *
 * <p>Reference: Bloch, J. (2018). <i>Effective Java</i> (3rd ed.). Addison-Wesley, Item 60 (avoid
 * {@code float} and {@code double} where exact answers are required).
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley, "Money" (rounding belongs to the arithmetic and has to be decided, not
 * inherited).
 */
public final class Rounding {

    /**
     * Precision of intermediate divisions: 34 significant digits, half-even. Used for a value that
     * is still being calculated, never for one about to be stored or displayed.
     */
    public static final MathContext DIVISION = MathContext.DECIMAL128;

    /** Scale of a stored USDT amount: profit and loss, fee, funding, margin (NUMERIC(28,8)). */
    public static final int AMOUNT_SCALE = 8;

    private Rounding() {}

    /**
     * Divides two values at {@link #DIVISION} precision, for use inside a longer calculation.
     *
     * @throws ArithmeticException if the divisor is zero
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        Objects.requireNonNull(dividend, "dividend must not be null");
        Objects.requireNonNull(divisor, "divisor must not be null");
        return dividend.divide(divisor, DIVISION);
    }

    /**
     * Rounds a quantity down to a whole multiple of the pair step size. Down and never up: the
     * quantity that comes out of a risk calculation is the largest one the risk budget allows, so
     * the nearest tradable quantity below it is the only safe choice.
     *
     * @param quantity the calculated quantity, zero or positive
     * @param stepSize the step size of the pair, strictly positive
     * @throws IllegalArgumentException if the quantity is negative or the step size is not positive
     */
    public static BigDecimal toStepSize(BigDecimal quantity, BigDecimal stepSize) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        requirePositive(stepSize, "stepSize");
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must not be negative, was " + quantity);
        }
        return toMultiple(quantity, stepSize, RoundingMode.DOWN);
    }

    /**
     * Rounds a price to a whole multiple of the pair tick size, in the direction the caller asks
     * for: {@link RoundingMode#HALF_UP} for a price a user typed, {@link RoundingMode#CEILING} or
     * {@link RoundingMode#FLOOR} for a liquidation price, which is rounded towards the position so
     * that the estimate stays conservative.
     *
     * @param price the price to round, zero or positive
     * @param tickSize the tick size of the pair, strictly positive
     * @param mode the direction, chosen by the caller
     * @throws IllegalArgumentException if the price is negative or the tick size is not positive
     */
    public static BigDecimal toTickSize(BigDecimal price, BigDecimal tickSize, RoundingMode mode) {
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        requirePositive(tickSize, "tickSize");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative, was " + price);
        }
        return toMultiple(price, tickSize, mode);
    }

    /**
     * Brings a USDT amount to the scale it is stored with, rounding half to even so that a long
     * series of roundings does not drift upwards the way half-up does.
     */
    public static BigDecimal toAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal toMultiple(BigDecimal value, BigDecimal unit, RoundingMode mode) {
        BigDecimal multiples = value.divide(unit, 0, mode);
        return multiples.multiply(unit).setScale(scaleOf(unit), RoundingMode.UNNECESSARY);
    }

    private static int scaleOf(BigDecimal unit) {
        return Math.max(unit.stripTrailingZeros().scale(), 0);
    }

    private static void requirePositive(BigDecimal unit, String name) {
        Objects.requireNonNull(unit, name + " must not be null");
        if (unit.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + unit);
        }
    }
}
