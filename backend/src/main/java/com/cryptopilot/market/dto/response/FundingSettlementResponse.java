package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One settled funding rate, at its settlement instant rounded to the minute.
 *
 * <p>Rule: UC-09; BR-11, BR-37; D-46.
 *
 * @param fundingTime the settlement instant
 * @param fundingRate the rate that was charged
 * @param markPrice the mark price at settlement
 */
public record FundingSettlementResponse(Instant fundingTime, BigDecimal fundingRate, BigDecimal markPrice) {}
