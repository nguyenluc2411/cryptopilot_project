package com.cryptopilot.trading.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One change of the levels of a position, as SCR-18 and the journal show its history.
 *
 * <p>Rule: UC-21; D-124.
 *
 * @param changedAt when the change was made; the new levels act from the candle that opened at or after it
 * @param oldStopLoss the stop loss before the change
 * @param oldTakeProfit the take profit before the change
 * @param newStopLoss the stop loss after the change
 * @param newTakeProfit the take profit after the change
 */
public record PositionLevelChangeResponse(
        Instant changedAt,
        BigDecimal oldStopLoss,
        BigDecimal oldTakeProfit,
        BigDecimal newStopLoss,
        BigDecimal newTakeProfit) {}
