package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the {@code trading} module tells other modules about one Trader's plan, and the one thing paper trading tells
 * it back: that the order a plan was applied with filled (RF-33).
 *
 * <p>Rule: BR-33, BR-51; TECHNICAL_DESIGN 7.14; D-117; RF-33.
 */
public interface TradingPlanApi {

    /**
     * The Trader's own plan, for the AI Assistant's context snapshot.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the Trader has no such plan (MSG41); a
     *     plan of another Trader is reported the same way
     */
    PlanContext planContext(UUID userId, UUID planId);

    /**
     * The terms the Trader's ACTIVE plan is applied with (RF-33).
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the Trader has no such plan (MSG41)
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_STATE_INVALID} when it is not ACTIVE
     */
    PlanOrderTerms termsToApply(UUID userId, UUID planId);

    /** The terms of a plan whatever its status, for the journal record of its order; empty when there is none. */
    Optional<PlanOrderTerms> terms(UUID planId);

    /**
     * The order a plan was applied with filled for the first time: the plan goes from ACTIVE to EXECUTED with the
     * fill's price, time and liquidity role, once (BR-33), and the Trader is told (MSG22). Joins the fill's
     * transaction.
     *
     * @param orderId the paper order
     * @param buy whether the fill bought: only a fill on the plan's entry side (BUY for LONG, SELL for SHORT) executes
     *     it; its take profit or stop loss filling first changes nothing
     * @return whether the plan was ACTIVE and is now EXECUTED
     */
    boolean entryFilled(UUID planId, UUID orderId, boolean buy, BigDecimal price, boolean maker, Instant at);
}
