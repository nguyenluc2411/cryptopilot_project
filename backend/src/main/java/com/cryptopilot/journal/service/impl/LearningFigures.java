package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * The learning figures derived from the stored data of trades, never stored themselves, as the net P/L is not. Every
 * ratio is rounded half-even to {@link #R_SCALE} places, the scale the entry deviation is stored at; a ratio with an
 * unknown input or a risk that is not positive is {@code null}.
 *
 * <p>Rule: BR-42, BR-70; D-97, D-111 (M4).
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (R-multiple,
 * expectancy); Sweeney, J. (1996). <i>Maximum Adverse Excursion</i>. Wiley (MAE, MFE).
 */
public final class LearningFigures {

    /** The scale of every ratio in R. */
    public static final int R_SCALE = 8;

    private LearningFigures() {}

    /**
     * R-multiple = net P/L / planned risk amount: the realized R of BR-42.
     *
     * <p>Rule: BR-42, BR-70.
     */
    public static BigDecimal rMultiple(BigDecimal netPnl, BigDecimal riskAmount) {
        if (netPnl == null || !positive(riskAmount)) {
            return null;
        }
        return ratio(netPnl, riskAmount);
    }

    /**
     * Maximum adverse excursion in R = (entry − most adverse price) × quantity / planned risk for a LONG, the mirror for
     * a SHORT; 0 when the price never went against the position.
     *
     * <p>Rule: BR-70.
     */
    public static BigDecimal maeR(
            Direction direction, BigDecimal entry, BigDecimal adverse, BigDecimal quantity, BigDecimal riskAmount) {
        if (adverse == null) {
            return null;
        }
        return excursionR(
                direction == Direction.LONG ? entry.subtract(adverse) : adverse.subtract(entry), quantity, riskAmount);
    }

    /**
     * Maximum favourable excursion in R = (most favourable price − entry) × quantity / planned risk for a LONG, the
     * mirror for a SHORT.
     *
     * <p>Rule: BR-70.
     */
    public static BigDecimal mfeR(
            Direction direction, BigDecimal entry, BigDecimal favorable, BigDecimal quantity, BigDecimal riskAmount) {
        if (favorable == null) {
            return null;
        }
        return excursionR(
                direction == Direction.LONG ? favorable.subtract(entry) : entry.subtract(favorable),
                quantity,
                riskAmount);
    }

    /**
     * Expectancy in R = the mean of the known R-multiples; {@code null} without one.
     *
     * <p>Rule: BR-70.
     */
    public static BigDecimal expectancy(List<BigDecimal> rMultiples) {
        List<BigDecimal> known = rMultiples.stream().filter(Objects::nonNull).toList();
        if (known.isEmpty()) {
            return null;
        }
        return ratio(known.stream().reduce(BigDecimal.ZERO, BigDecimal::add), BigDecimal.valueOf(known.size()));
    }

    /**
     * Cost of late entry = the sum of the known entry deviations in R; 0 without one.
     *
     * <p>Rule: BR-69, BR-70.
     */
    public static BigDecimal costOfLateEntry(List<BigDecimal> deviations) {
        return deviations.stream()
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(R_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal excursionR(BigDecimal distance, BigDecimal quantity, BigDecimal riskAmount) {
        if (!positive(riskAmount)) {
            return null;
        }
        return ratio(distance.multiply(quantity), riskAmount);
    }

    private static BigDecimal ratio(BigDecimal dividend, BigDecimal divisor) {
        return Rounding.divide(dividend, divisor).setScale(R_SCALE, RoundingMode.HALF_EVEN);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
