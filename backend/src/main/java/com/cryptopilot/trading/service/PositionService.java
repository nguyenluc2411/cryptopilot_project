package com.cryptopilot.trading.service;

import com.cryptopilot.trading.dto.response.CloseEstimateResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import java.util.UUID;

/**
 * The Trader's manual close of an open simulated position (UC-21, SCR-19). A plan is always addressed with its owner:
 * another Trader's plan is not found. Implemented by
 * {@link com.cryptopilot.trading.service.impl.PositionServiceImpl}.
 *
 * <p>Rule: UC-21, BR-39; MSG21, MSG23.
 */
public interface PositionService {

    /**
     * What closing the plan's OPEN position now would give, at the current last price; nothing is stored.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the plan is not the caller's (404)
     * @throws com.cryptopilot.trading.exception.IllegalPlanStateException when the plan has no OPEN position (409)
     * @throws com.cryptopilot.common.exception.BusinessException {@code MARKET_PRICE_UNAVAILABLE} when no current
     *     last price is cached (503)
     */
    CloseEstimateResponse closeEstimate(UUID userId, UUID planId);

    /**
     * Closes the plan's OPEN position at the current last price as a taker (BR-39), with the same compare-and-set as
     * the engine's exits, so a close racing an exit lets exactly one through.
     *
     * @return the plan, with its position CLOSED by MANUAL
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the plan is not the caller's (404)
     * @throws com.cryptopilot.trading.exception.IllegalPlanStateException when the plan has no OPEN position, or the
     *     engine closed it first (409)
     * @throws com.cryptopilot.common.exception.BusinessException {@code MARKET_PRICE_UNAVAILABLE} when no current
     *     last price is cached (503)
     */
    TradingPlanResponse closePosition(UUID userId, UUID planId);
}
