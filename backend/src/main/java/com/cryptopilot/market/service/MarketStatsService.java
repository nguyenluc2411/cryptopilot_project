package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.FuturesMetricsResponse;
import com.cryptopilot.market.dto.response.FuturesStatsResponse;
import com.cryptopilot.market.dto.response.SpotStatsResponse;
import java.time.Instant;

/**
 * The stored snapshots and futures metrics of a pair (UC-09); implemented by
 * {@link com.cryptopilot.market.service.impl.MarketStatsServiceImpl}.
 *
 * <p>Rule: UC-09, BR-07, BR-10, BR-11; SRS 3.3.1, 3.3.3; D-45, D-46, D-48.
 */
public interface MarketStatsService {

    /**
     * The latest stored ticker of a Spot pair, with its instant.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     Spot (BR-07)
     */
    SpotStatsResponse spotStats(String symbol);

    /**
     * The latest stored mark price, open interest, long/short ratio and settlement of a futures pair, each with its
     * own instant (D-45).
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     futures (BR-07)
     */
    FuturesStatsResponse futuresStats(String symbol);

    /**
     * The open interest, long/short ratio and settled funding series of a futures pair over {@code [from, to)}, only
     * the instants that hold a value (D-45), with the earliest stored instant of each (BR-10).
     *
     * @param from the start of the range; {@code null} for the configured default range before {@code to}
     * @param to the end of the range, exclusive; {@code null} for now
     * @throws com.cryptopilot.common.exception.BusinessException {@code VALIDATION_FAILED} (MSG01) for a range that
     *     ends in the future, is empty or backwards, or is longer than one request may ask for
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     futures (BR-07)
     */
    FuturesMetricsResponse futuresMetrics(String symbol, Instant from, Instant to);
}
