package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.FuturesMetricsResponse;
import com.cryptopilot.market.dto.response.FuturesStatsResponse;
import com.cryptopilot.market.dto.response.SpotStatsResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * The stored snapshots and futures metrics of a pair (UC-09); implemented by
 * {@link com.cryptopilot.market.service.impl.MarketStatsServiceImpl}.
 *
 * <p>The Futures data beyond the price is part of a plan ({@code FUTURES_ANALYSIS}, D-60): the stats answer its blocks
 * only to a caller whose plan includes it, and the metrics refuse everybody else.
 *
 * <p>Rule: UC-09, BR-07, BR-10, BR-11, BR-62; SRS 3.3.1, 3.3.3; D-45, D-46, D-48, D-60.
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
     * own instant (D-45). The price block is public; the open interest, long/short ratio and settlement blocks are
     * {@code null} unless the caller's plan includes {@code FUTURES_ANALYSIS}.
     *
     * @param caller the signed-in caller, or {@code null} for a Guest
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     futures (BR-07)
     */
    FuturesStatsResponse futuresStats(String symbol, UUID caller);

    /**
     * The open interest, long/short ratio and settled funding series of a futures pair over {@code [from, to)}, only
     * the instants that hold a value (D-45), with the earliest stored instant of each (BR-10).
     *
     * @param from the start of the range; {@code null} for the configured default range before {@code to}
     * @param to the end of the range, exclusive; {@code null} for now
     * @param caller the signed-in caller, or {@code null} for a Guest
     * @throws com.cryptopilot.common.exception.BusinessException {@code AUTHENTICATION_REQUIRED} (MSG44) for a Guest
     *     and {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29) for a plan without {@code FUTURES_ANALYSIS}, both before the
     *     request is read further; {@code VALIDATION_FAILED} (MSG01) for a range that
     *     ends in the future, is empty or backwards, or is longer than one request may ask for
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     futures (BR-07)
     */
    FuturesMetricsResponse futuresMetrics(String symbol, Instant from, Instant to, UUID caller);
}
