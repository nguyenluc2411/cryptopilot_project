package com.cryptopilot.trading.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.model.PlanListQuery;
import java.util.UUID;

/**
 * The trading plan use cases of SCR-16 to SCR-18 (UC-16 to UC-20). A plan is always addressed with its owner: another
 * Trader's plan is not found.
 *
 * <p>Rule: UC-16 to UC-20; BR-21 to BR-33, BR-62, BR-66.
 */
public interface TradingPlanService {

    /** The live risk panel of the values entered; nothing is saved. */
    PlanCalculationResponse calculate(UUID userId, CreateTradingPlanRequest request);

    /** Saves a new plan as a DRAFT, and activates it when the request asks to (UC-16). */
    TradingPlanResponse create(UUID userId, CreateTradingPlanRequest request);

    /** Saves new values of a DRAFT plan, and activates it when the request asks to (UC-17). */
    TradingPlanResponse update(UUID userId, UUID planId, UpdateTradingPlanRequest request);

    /** Activates a DRAFT plan within the plan's limit of ACTIVE plans and open positions (UC-18, BR-62). */
    TradingPlanResponse activate(UUID userId, UUID planId);

    /** Cancels a DRAFT or ACTIVE plan (UC-19). */
    TradingPlanResponse cancel(UUID userId, UUID planId);

    /** A plan in full (SCR-18). */
    TradingPlanResponse get(UUID userId, UUID planId);

    /** A page of the Trader's plans (SCR-16), newest first. */
    PageResponse<TradingPlanSummaryResponse> list(UUID userId, PlanListQuery query);
}
