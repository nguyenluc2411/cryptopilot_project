package com.cryptopilot.trading.calculator;

import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rules a new stop loss or take profit of an OPEN position must pass, and the risk an open position still carries
 * at its current stop loss.
 *
 * <ul>
 *   <li>Against the current last price, never the mark price: a LONG needs {@code stopLoss < last < takeProfit}, a
 *       SHORT {@code takeProfit < last < stopLoss}. A level the price has already reached is refused, never executed at
 *       once (MSG16 on the field).
 *   <li>Futures: the stop loss must stay before the position's liquidation price, as at entry, and this is blocking
 *       (MSG16 on {@code stopLoss}).
 *   <li>The current risk is {@code quantity × |entry − stop loss|} while the stop is on the losing side of the entry,
 *       and 0 once it is at or past the entry: such a stop locks a result of at least the price result at the entry.
 * </ul>
 *
 * <p>Rule: BR-22, BR-28, BR-29; UC-21; D-124 (TP-E).
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill, ch. 12 (the
 * open risk of a position is the loss to its current stop, which a trailing stop reduces).
 */
public final class PositionLevelRule {

    private static final String WRONG_SIDE = "MSG16";

    private PositionLevelRule() {}

    /**
     * The violations of the new levels; a {@code null} level is not changed and not checked.
     *
     * <p>Rule: BR-22, BR-28; D-124.
     *
     * @param lastPrice the current last price of the pair
     * @param liquidationPrice the position's liquidation price on Futures; {@code null} on Spot or when no price
     *     liquidates it
     */
    public static List<InputViolation> violations(
            Direction direction,
            BigDecimal newStopLoss,
            BigDecimal newTakeProfit,
            BigDecimal lastPrice,
            BigDecimal liquidationPrice) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(lastPrice, "lastPrice");
        boolean isLong = direction == Direction.LONG;
        List<InputViolation> violations = new ArrayList<>();
        if (newStopLoss != null) {
            int compared = newStopLoss.compareTo(lastPrice);
            if (isLong ? compared >= 0 : compared <= 0) {
                violations.add(new InputViolation(
                        "stopLoss",
                        WRONG_SIDE,
                        "the stop loss of an open " + direction + " position must be " + (isLong ? "below" : "above")
                                + " the current price " + lastPrice.toPlainString()));
            } else if (beyondLiquidation(isLong, newStopLoss, liquidationPrice)) {
                violations.add(new InputViolation(
                        "stopLoss",
                        WRONG_SIDE,
                        "the stop loss of an open " + direction + " position must be " + (isLong ? "above" : "below")
                                + " its liquidation price " + liquidationPrice.toPlainString()));
            }
        }
        if (newTakeProfit != null) {
            int compared = newTakeProfit.compareTo(lastPrice);
            if (isLong ? compared <= 0 : compared >= 0) {
                violations.add(new InputViolation(
                        "takeProfit",
                        WRONG_SIDE,
                        "the take profit of an open " + direction + " position must be " + (isLong ? "above" : "below")
                                + " the current price " + lastPrice.toPlainString()));
            }
        }
        return violations;
    }

    /**
     * The risk an open position carries at its current stop loss, at full precision.
     *
     * <p>Rule: BR-29; D-124.
     *
     * @param stopLoss the current stop loss; {@code null} counts no risk
     */
    public static BigDecimal currentRisk(
            Direction direction, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal quantity) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(quantity, "quantity");
        if (stopLoss == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal loss = direction == Direction.LONG ? entryPrice.subtract(stopLoss) : stopLoss.subtract(entryPrice);
        return loss.signum() <= 0 ? BigDecimal.ZERO : loss.multiply(quantity);
    }

    /**
     * Whether a stop loss lies further from the entry than another one: a LONG stop lower, a SHORT stop higher.
     *
     * <p>Rule: BR-42; D-124.
     */
    public static boolean isFurtherFromEntry(Direction direction, BigDecimal stopLoss, BigDecimal reference) {
        if (stopLoss == null || reference == null) {
            return false;
        }
        int compared = stopLoss.compareTo(reference);
        return direction == Direction.LONG ? compared < 0 : compared > 0;
    }

    // At or past the liquidation price: a LONG at or below it, a SHORT at or above it (BR-28).
    private static boolean beyondLiquidation(boolean isLong, BigDecimal stopLoss, BigDecimal liquidationPrice) {
        if (liquidationPrice == null || liquidationPrice.signum() <= 0) {
            return false;
        }
        int compared = stopLoss.compareTo(liquidationPrice);
        return isLong ? compared <= 0 : compared >= 0;
    }
}
