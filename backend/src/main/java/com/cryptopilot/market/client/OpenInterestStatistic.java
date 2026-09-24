package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The open interest of one futures symbol at the end of one period
 * ({@code GET /futures/data/openInterestHist}).
 *
 * <p>Open interest is the number of contracts open and not yet settled at an instant; its value is that
 * quantity at the price of the instant, in the quote asset. The exchange keeps the latest month only, so older
 * history exists only in the system's own snapshots (BR-10).
 *
 * <p>Rule: BR-09, BR-10; NSF-04.
 *
 * <p>Reference: Binance. <i>USDⓈ-M Futures API</i>, "Open Interest Statistics" (periods from 5m; limit at most
 * 500; IP weight 0, at most 1000 requests per 5 minutes; "Only the data of the latest 1 month is available").
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
 * (open interest: the total number of contracts outstanding).
 *
 * @param symbol the futures symbol
 * @param openInterest the open contracts, in base-asset units
 * @param openInterestValue the value of the open contracts, in the quote asset
 * @param timestamp the instant of the period
 */
public record OpenInterestStatistic(
        String symbol, BigDecimal openInterest, BigDecimal openInterestValue, Instant timestamp) {}
