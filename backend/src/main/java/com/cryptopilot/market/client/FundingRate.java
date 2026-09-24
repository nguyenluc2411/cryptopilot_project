package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One settled funding rate ({@code GET /fapi/v1/fundingRate}), the fact a funding settlement snapshot
 * is written from (NSF-04, TECHNICAL_DESIGN 7.1 step 8).
 *
 * <p>Rule: BR-09, BR-11, BR-37; NSF-04.
 *
 * @param symbol the futures symbol
 * @param fundingTime the settlement instant
 * @param fundingRate the settled rate
 * @param markPrice the mark price at settlement, or {@code null} when the exchange sent none
 */
public record FundingRate(String symbol, Instant fundingTime, BigDecimal fundingRate, BigDecimal markPrice) {}
