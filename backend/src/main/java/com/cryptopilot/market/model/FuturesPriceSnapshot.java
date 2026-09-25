package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The mark price columns of a {@code futures_market_data} row, which NSF-03 writes every minute.
 *
 * <p>Rule: NSF-03; BR-11; D-45.
 *
 * @param snapshotTime the minute the values were written for
 * @param markPrice the mark price
 * @param indexPrice the index price
 * @param fundingRate the predicted funding rate of the coming settlement (not a settled rate)
 * @param nextFundingTime the next settlement, as the source reported it
 */
public record FuturesPriceSnapshot(
        Instant snapshotTime,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal fundingRate,
        Instant nextFundingTime) {}
