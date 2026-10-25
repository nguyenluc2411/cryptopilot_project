package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One change of the stop loss and take profit of an OPEN simulated position, as the journal keeps it in the history of
 * the position. The levels before the change are in force for every candle that opened before {@code changedAt}, the
 * new ones from it on, in live processing and in a replay alike.
 *
 * <p>Rule: BR-35, NSF-07; UC-21; D-77, D-79, D-119 (5), D-124.
 *
 * @param positionId the journal record
 * @param changedAt when the change was made; the instant from which the new levels act
 * @param oldStopLoss the stop loss before the change
 * @param oldTakeProfit the take profit before the change
 * @param newStopLoss the stop loss after the change
 * @param newTakeProfit the take profit after the change
 */
public record PositionLevelChange(
        UUID positionId,
        Instant changedAt,
        BigDecimal oldStopLoss,
        BigDecimal oldTakeProfit,
        BigDecimal newStopLoss,
        BigDecimal newTakeProfit) {

    public PositionLevelChange {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(changedAt, "changedAt");
    }

    /**
     * The open time of the first 1-minute candle the new levels act on: the next minute, or {@code changedAt} itself
     * when the change was made exactly at a candle's open.
     *
     * <p>Rule: NSF-07; D-77, D-124 (Q-80).
     */
    public Instant effectiveFrom() {
        Instant minute = changedAt.truncatedTo(ChronoUnit.MINUTES);
        return minute.equals(changedAt) ? changedAt : minute.plus(1, ChronoUnit.MINUTES);
    }
}
