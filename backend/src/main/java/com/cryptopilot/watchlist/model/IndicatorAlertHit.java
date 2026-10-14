package com.cryptopilot.watchlist.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An INDICATOR alert whose condition a closed candle met. The database still decides whether the trigger is recorded.
 *
 * <p>Rule: NSF-06, BR-19, BR-20.
 *
 * @param alert the alert as read for the candle
 * @param symbol the pair's symbol, for the notification
 * @param observedValue the indicator value that met the condition
 * @param barOpenTime the open time of the closed candle
 * @param at when it was evaluated
 */
public record IndicatorAlertHit(
        IndicatorAlert alert, String symbol, BigDecimal observedValue, Instant barOpenTime, Instant at) {}
