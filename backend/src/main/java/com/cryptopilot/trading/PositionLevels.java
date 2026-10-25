package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The levels of a simulated position as the journal holds them: the current ones the exits read, the original ones of
 * the fill, and the history of their changes, which the web and the AI review read to point out a stop that was moved
 * away.
 *
 * <p>Rule: BR-35, BR-42; UC-20, UC-21; D-119 (5), D-124.
 *
 * @param stopLoss the current stop loss
 * @param takeProfit the current take profit
 * @param originalStopLoss the stop loss at the fill, never changed
 * @param originalTakeProfit the take profit at the fill, never changed
 * @param stopWidened whether a stop loss was ever moved further from the entry than the original one
 * @param changeCount how many changes were made
 * @param changes every change, oldest first
 */
public record PositionLevels(
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal originalStopLoss,
        BigDecimal originalTakeProfit,
        boolean stopWidened,
        int changeCount,
        List<PositionLevelChange> changes) {

    public PositionLevels {
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}
