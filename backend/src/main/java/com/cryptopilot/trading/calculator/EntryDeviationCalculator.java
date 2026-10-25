package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.EntryDeviation;
import com.cryptopilot.trading.model.EntryDeviation.Verdict;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The entry deviation of a fill, in R: how far the fill price lies from the planned entry, measured in units of the
 * planned stop distance and signed so that a worse entry is positive. At the activation of a MARKET plan it decides
 * whether the entry goes on, needs the Trader's confirmation, or is refused.
 *
 * <p>Rule: BR-69; D-111 (M2), D-112 (R7); TECHNICAL_DESIGN 7.8.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (R, the initial
 * risk per unit, as the measure of every price move of a trade).
 */
public final class EntryDeviationCalculator {

    /** The scale a deviation is recorded and compared at, as the journal column holds it. */
    public static final int DEVIATION_SCALE = 8;

    private EntryDeviationCalculator() {}

    /**
     * {@code d × (fill − planned) / |planned − stop loss|}, with d = +1 for LONG and −1 for SHORT, rounded half-even to
     * {@link #DEVIATION_SCALE}. Positive when a LONG paid more or a SHORT sold for less than planned.
     *
     * <p>Rule: BR-69; D-111 (M2).
     *
     * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill.
     *
     * @param plannedEntry the planned entry price
     * @param fill the fill price, or the expected fill
     * @param stopLoss the plan's stop loss, different from the planned entry
     * @throws IllegalArgumentException when the stop loss equals the planned entry
     */
    public static BigDecimal deviationR(
            Direction direction, BigDecimal plannedEntry, BigDecimal fill, BigDecimal stopLoss) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(plannedEntry, "plannedEntry");
        Objects.requireNonNull(fill, "fill");
        Objects.requireNonNull(stopLoss, "stopLoss");
        BigDecimal stopDistance = plannedEntry.subtract(stopLoss).abs();
        if (stopDistance.signum() == 0) {
            throw new IllegalArgumentException("the stop loss " + stopLoss + " equals the planned entry");
        }
        BigDecimal move = fill.subtract(plannedEntry);
        BigDecimal signed = direction == Direction.LONG ? move : move.negate();
        return Rounding.divide(signed, stopDistance).setScale(DEVIATION_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * The decision on a MARKET entry at activation. The limit is the plan's own one when it is set and stricter than
     * {@code blockR}, otherwise {@code blockR}. An expected fill at or past the take profit is refused whatever its
     * deviation; otherwise a deviation above the limit is refused, one from {@code confirmR} up to and including the
     * limit needs confirmation, and anything below {@code confirmR}, a better price included, goes on. A limit below
     * {@code confirmR} therefore leaves no confirmation band.
     *
     * <p>Rule: BR-69; D-111 (M2), D-112 (R7).
     *
     * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill.
     *
     * @param plannedEntry the reference price of the plan's last draft save
     * @param expectedFill the current last price moved by the slippage against the Trader
     * @param confirmR {@code ENTRY_DEVIATION_CONFIRM_R}
     * @param blockR {@code ENTRY_DEVIATION_BLOCK_R}
     * @param planLimitR the plan's {@code max_entry_deviation_r}, or {@code null} for none
     */
    public static EntryDeviation atMarketActivation(
            Direction direction,
            BigDecimal plannedEntry,
            BigDecimal expectedFill,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal confirmR,
            BigDecimal blockR,
            BigDecimal planLimitR) {
        Objects.requireNonNull(takeProfit, "takeProfit");
        Objects.requireNonNull(confirmR, "confirmR");
        Objects.requireNonNull(blockR, "blockR");
        BigDecimal deviation = deviationR(direction, plannedEntry, expectedFill, stopLoss);
        BigDecimal limit = planLimitR == null ? blockR : planLimitR.min(blockR);
        int toTakeProfit = expectedFill.compareTo(takeProfit);
        boolean takeProfitReached = direction == Direction.LONG ? toTakeProfit >= 0 : toTakeProfit <= 0;
        Verdict verdict;
        if (takeProfitReached || deviation.compareTo(limit) > 0) {
            verdict = Verdict.REFUSE;
        } else if (deviation.compareTo(confirmR) >= 0) {
            verdict = Verdict.CONFIRM;
        } else {
            verdict = Verdict.PROCEED;
        }
        return new EntryDeviation(deviation, limit, takeProfitReached, verdict);
    }
}
