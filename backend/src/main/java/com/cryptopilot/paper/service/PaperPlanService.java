package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.response.AppliedPlanResponse;
import java.util.UUID;

/**
 * Applies a trading plan: places the paper orders that carry it out, with the plan's own quantity, entry and levels
 * (RF-33). The plan is EXECUTED when its entry order fills, and its fills reach the demo wallet and the journal as any
 * other paper fill does.
 *
 * <p>Rule: BR-33, BR-71; D-117; RF-33.
 */
public interface PaperPlanService {

    /**
     * Places the plan's orders in one transaction: all of them or none.
     *
     * <ul>
     *   <li>Spot, MARKET entry: a MARKET buy, then the take profit and stop loss as an OCO sale of what it bought
     *       (only the stop loss when the plan has no take profit).
     *   <li>Spot, LIMIT entry: an OTOCO, the limit buy working and the OCO placed once it fills (an OTO with the stop
     *       loss when there is no take profit).
     *   <li>Spot, STOP entry: a STOP_LOSS buy at the entry; the levels are the Trader's to add once it fills.
     *   <li>Futures: the plan's leverage and isolated margin on the pair, then a MARKET or LIMIT order with its take
     *       profit and stop loss; a STOP entry is a STOP_MARKET conditional order without them.
     * </ul>
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the Trader has no such plan (MSG41)
     * @throws com.cryptopilot.common.exception.BusinessException {@code TRADING_PLAN_STATUS_TRANSITION_INVALID} when it
     *     is not ACTIVE, {@code PLAN_ALREADY_APPLIED} when an order of it still works, and every refusal of the
     *     order paths ({@code PAPER_INSUFFICIENT_BALANCE} and the filters among them)
     */
    AppliedPlanResponse apply(UUID userId, UUID planId);
}
