package com.cryptopilot.trading.service;

import com.cryptopilot.trading.dto.request.ChangePositionLevelsRequest;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import java.util.UUID;

/**
 * Changing the stop loss and take profit of the OPEN position of a Trader's executed plan (SCR-18). Another Trader's
 * plan is not found.
 *
 * <p>Rule: BR-22, BR-28, BR-29, BR-35; UC-21; D-124.
 */
public interface PositionLevelService {

    /**
     * Replaces the stop loss, the take profit or both of the plan's OPEN position, checked against the current last
     * price, and answers the plan with its updated position.
     */
    TradingPlanResponse changeLevels(UUID userId, UUID planId, ChangePositionLevelsRequest request);
}
