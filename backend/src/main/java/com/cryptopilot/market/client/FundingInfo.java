package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The funding settings the exchange publishes for one futures symbol ({@code GET /fapi/v1/fundingInfo}).
 *
 * <p>The exchange lists only symbols "that had FundingRateCap/FundingRateFloor/fundingIntervalHours
 * adjustment", so a symbol missing from the list has no interval stated by this source. The caller must then
 * treat the interval as unknown: BR-11 forbids assuming one, 8 hours included.
 *
 * <p>Rule: BR-09, BR-11; NSF-04.
 *
 * <p>Reference: Binance. <i>USDⓈ-M Futures API</i>, "Get Funding Rate Info" (weight 0; shares the 500 requests
 * per 5 minutes per IP limit with {@code GET /fapi/v1/fundingRate}).
 *
 * @param symbol the futures symbol
 * @param fundingInterval the time between two settlements, from {@code fundingIntervalHours}
 * @param adjustedFundingRateCap the highest rate the exchange will settle
 * @param adjustedFundingRateFloor the lowest rate the exchange will settle
 */
public record FundingInfo(
        String symbol,
        Duration fundingInterval,
        BigDecimal adjustedFundingRateCap,
        BigDecimal adjustedFundingRateFloor) {}
