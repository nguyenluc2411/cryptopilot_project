package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One settled funding rate of {@code funding_rate_history}, at its normalized instant.
 *
 * <p>Rule: NSF-04; BR-11, BR-37; D-46.
 *
 * @param fundingTime the settlement instant, rounded to the minute
 * @param fundingRate the rate that was charged
 * @param markPrice the mark price at settlement
 */
public record FundingSettlement(Instant fundingTime, BigDecimal fundingRate, BigDecimal markPrice) {}
