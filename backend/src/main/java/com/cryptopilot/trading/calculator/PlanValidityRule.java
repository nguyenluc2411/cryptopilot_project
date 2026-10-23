package com.cryptopilot.trading.calculator;

import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The checks of a plan's validity values that the risk calculation does not make: where its invalidation price may
 * lie and how far its own entry deviation limit may go. Pure functions; the caller reads the current price and the
 * settings and reports a violation on its field.
 *
 * <p>Rule: BR-33, BR-68, BR-69; D-111, D-112.
 */
public final class PlanValidityRule {

    private static final String OUT_OF_RANGE = "MSG15";
    private static final String WRONG_SIDE = "MSG16";

    private PlanValidityRule() {}

    /**
     * The invalidation price of a waiting plan must lie on the far side of the current price from the entry, so the
     * price can reach it before the entry: above the current price for a LIMIT LONG or a STOP SHORT, below it for a
     * LIMIT SHORT or a STOP LONG. Equal is refused, since the level would already be reached. Checked at save and at
     * activation (MSG16 on {@code invalidationPrice}).
     *
     * <p>Rule: BR-68; D-111 (M3), D-112.
     *
     * <p>Reference: Binance Futures documentation, order types (a stop order that would trigger immediately is
     * rejected).
     *
     * @param entryType LIMIT or STOP; a MARKET plan never carries one
     * @param direction LONG or SHORT
     * @param invalidationPrice the plan's invalidation price, or {@code null} for none
     * @param currentPrice the current price of the pair
     * @return the violation, or empty when there is no invalidation price or it lies on the far side
     */
    public static Optional<InputViolation> invalidationPrice(
            EntryType entryType, Direction direction, BigDecimal invalidationPrice, BigDecimal currentPrice) {
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(direction, "direction");
        if (invalidationPrice == null) {
            return Optional.empty();
        }
        if (entryType == EntryType.MARKET) {
            throw new IllegalArgumentException("a MARKET plan has no invalidation price");
        }
        Objects.requireNonNull(currentPrice, "currentPrice");
        boolean mustBeAbove = (entryType == EntryType.LIMIT) == (direction == Direction.LONG);
        int compared = invalidationPrice.compareTo(currentPrice);
        if (mustBeAbove ? compared > 0 : compared < 0) {
            return Optional.empty();
        }
        return Optional.of(new InputViolation(
                "invalidationPrice",
                WRONG_SIDE,
                "the invalidation price of a " + entryType + " " + direction + " plan must be "
                        + (mustBeAbove ? "above" : "below") + " the current price " + currentPrice.toPlainString()));
    }

    /**
     * A STOP whose trigger the last price has already reached may not be activated: at or above the stop price for a
     * LONG, at or below it for a SHORT (MSG16 on {@code entryPrice}). It is never turned into a MARKET entry.
     *
     * <p>Rule: BR-33; D-112 (Q-72).
     *
     * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges</i>. Oxford University Press, ch. 4 (a stop order
     * triggers at or through its stop price).
     *
     * @param direction LONG or SHORT
     * @param stopPrice the plan's stop price
     * @param lastPrice the current last price of the pair
     * @return the violation, or empty when the stop is not triggered yet
     */
    public static Optional<InputViolation> stopAlreadyTriggered(
            Direction direction, BigDecimal stopPrice, BigDecimal lastPrice) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(stopPrice, "stopPrice");
        Objects.requireNonNull(lastPrice, "lastPrice");
        int compared = lastPrice.compareTo(stopPrice);
        boolean triggered = direction == Direction.LONG ? compared >= 0 : compared <= 0;
        if (!triggered) {
            return Optional.empty();
        }
        return Optional.of(new InputViolation(
                "entryPrice",
                WRONG_SIDE,
                "the stop price of a STOP " + direction + " plan must be "
                        + (direction == Direction.LONG ? "above" : "below") + " the current price "
                        + lastPrice.toPlainString()));
    }

    /**
     * A plan's own limit on the entry deviation may only be stricter than the system's: above 0 and at most
     * {@code ENTRY_DEVIATION_BLOCK_R} (MSG15 on {@code maxEntryDeviationR}).
     *
     * <p>Rule: BR-69; D-111 (M2), D-112 (R7).
     *
     * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (risk
     * expressed in R, the initial risk per unit).
     *
     * @param maxEntryDeviationR the plan's limit in R, or {@code null} for none
     * @param blockR the system's block value in R
     * @return the violation, or empty when there is no limit or it is within range
     */
    public static Optional<InputViolation> maxEntryDeviation(BigDecimal maxEntryDeviationR, BigDecimal blockR) {
        Objects.requireNonNull(blockR, "blockR");
        if (maxEntryDeviationR == null
                || (maxEntryDeviationR.signum() > 0 && maxEntryDeviationR.compareTo(blockR) <= 0)) {
            return Optional.empty();
        }
        return Optional.of(new InputViolation(
                "maxEntryDeviationR",
                OUT_OF_RANGE,
                "the maximum entry deviation must be above 0 and at most " + blockR.toPlainString() + " R"));
    }
}
