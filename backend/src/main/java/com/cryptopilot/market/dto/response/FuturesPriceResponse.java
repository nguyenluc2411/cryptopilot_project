package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest stored mark price of a futures pair and the funding state that travels with it.
 *
 * <p>{@code fundingRate} is the <em>predicted</em> rate of the coming settlement, not a settled one; settled rates are
 * {@link FundingSettlementResponse}. {@code nextFundingTime} is the source's, never computed (BR-11).
 *
 * <p>Rule: UC-09; BR-11; SRS 3.3.1, 3.3.3; D-45.
 *
 * @param asOf the minute the values were stored for
 * @param markPrice the mark price
 * @param indexPrice the index price
 * @param fundingRate the predicted funding rate
 * @param nextFundingTime the next settlement, as the source reported it
 */
public record FuturesPriceResponse(
        Instant asOf, BigDecimal markPrice, BigDecimal indexPrice, BigDecimal fundingRate, Instant nextFundingTime) {}
