package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A change of the stop loss and take profit of an OPEN simulated position, as the journal stores it. Every value is
 * decided by the {@code trading} module; the journal compares the version, stores and decides nothing.
 *
 * <p>Rule: BR-35, BR-42; UC-21; D-119 (5), D-124.
 *
 * @param positionId the journal record
 * @param version the row's version the change was validated against; it only applies to that version
 * @param oldStopLoss the stop loss the change replaces
 * @param oldTakeProfit the take profit the change replaces
 * @param newStopLoss the new stop loss
 * @param newTakeProfit the new take profit
 * @param widensStop whether the new stop loss is further from the entry than the original one, so the row's
 *     {@code stop_widened} is set and stays set
 * @param changedAt when the change is made
 */
public record SimulatedLevelChange(
        UUID positionId,
        long version,
        BigDecimal oldStopLoss,
        BigDecimal oldTakeProfit,
        BigDecimal newStopLoss,
        BigDecimal newTakeProfit,
        boolean widensStop,
        Instant changedAt) {

    public SimulatedLevelChange {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(changedAt, "changedAt");
    }

    /** The change as the history of the position records it. */
    public PositionLevelChange history() {
        return new PositionLevelChange(positionId, changedAt, oldStopLoss, oldTakeProfit, newStopLoss, newTakeProfit);
    }
}
