package com.cryptopilot.market.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * The futures metrics of one pair over {@code [from, to)}: three sparse series, each listing only the instants that
 * hold a value — no point is filled in, interpolated or set to zero between readings (D-45) — and, for each, the
 * earliest instant the system has stored, so a chart can state the period actually available (BR-10).
 *
 * <p>Rule: UC-09; NSF-04; BR-10, BR-11; SRS 3.3.3; D-45, D-46.
 *
 * @param symbol the symbol
 * @param from the start of the range, inclusive
 * @param to the end of the range, exclusive
 * @param openInterest the open interest readings in the range, oldest first
 * @param longShortRatio the long/short account ratio readings in the range, oldest first
 * @param fundingSettlements the settled funding rates in the range, oldest first
 * @param availableFrom the earliest stored instant of each series
 */
public record FuturesMetricsResponse(
        String symbol,
        Instant from,
        Instant to,
        List<OpenInterestResponse> openInterest,
        List<LongShortRatioResponse> longShortRatio,
        List<FundingSettlementResponse> fundingSettlements,
        AvailableFrom availableFrom) {

    /**
     * The earliest stored instant of each series, each absent when nothing is stored (BR-10).
     *
     * @param openInterest the first open interest reading
     * @param longShortRatio the first long/short account ratio reading
     * @param fundingSettlements the first settled funding rate
     */
    public record AvailableFrom(Instant openInterest, Instant longShortRatio, Instant fundingSettlements) {}
}
