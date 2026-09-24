package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The mark price and funding state of one futures symbol ({@code GET /fapi/v1/premiumIndex}).
 *
 * <p>{@code nextFundingTime} is read from here and never computed: BR-11 forbids assuming a fixed
 * funding interval, and the exchange changes the interval per symbol.
 *
 * <p>Rule: BR-09, BR-11; NSF-04.
 *
 * @param symbol the futures symbol
 * @param markPrice the mark price, which liquidation is measured against
 * @param indexPrice the index price
 * @param lastFundingRate the predicted rate of the coming settlement (not a settled rate)
 * @param nextFundingTime when the next settlement happens
 * @param time the instant the exchange computed these values
 */
public record PremiumIndex(
        String symbol,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal lastFundingRate,
        Instant nextFundingTime,
        Instant time) {}
