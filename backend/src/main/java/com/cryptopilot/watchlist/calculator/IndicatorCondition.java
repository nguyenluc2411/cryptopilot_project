package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;

/**
 * Whether an indicator alert's condition holds on a closed candle, with the semantics PRICE alerts use (D-88): a
 * cross is edge-triggered, met only when the previous value was strictly on the other side of the level and the
 * current one reached it; GREATER_THAN and LESS_THAN are strict and hold on every candle they are true. A line cross
 * has no stored threshold (D-76) and crosses zero.
 *
 * <p>Rule: BR-20; TECHNICAL_DESIGN 7.9; D-76, D-88.
 */
public final class IndicatorCondition {

    private IndicatorCondition() {}

    /**
     * Whether the reading meets the condition.
     *
     * @param threshold the stored level, or {@code null} for a line cross, which compares with zero
     */
    public static boolean met(ConditionOperator condition, BigDecimal threshold, IndicatorReading reading) {
        BigDecimal level = threshold == null ? BigDecimal.ZERO : threshold;
        BigDecimal current = reading.current();
        BigDecimal previous = reading.previous();
        return switch (condition) {
            case CROSS_ABOVE -> previous != null && previous.compareTo(level) < 0 && current.compareTo(level) >= 0;
            case CROSS_BELOW -> previous != null && previous.compareTo(level) > 0 && current.compareTo(level) <= 0;
            case GREATER_THAN -> current.compareTo(level) > 0;
            case LESS_THAN -> current.compareTo(level) < 0;
        };
    }
}
