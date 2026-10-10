package com.cryptopilot.watchlist.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A PRICE alert whose condition the latest price met and whose trigger mode allows it to fire now. The database still
 * decides whether the trigger is recorded.
 *
 * <p>Rule: NSF-06, BR-19, BR-20.
 *
 * @param alert the alert as the engine holds it
 * @param observedValue the price that met the condition
 * @param barOpenTime the open time of the 1h candle of that price (BR-19)
 * @param at when the engine evaluated it
 */
public record PriceAlertHit(PriceAlert alert, BigDecimal observedValue, Instant barOpenTime, Instant at) {}
