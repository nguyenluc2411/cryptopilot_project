package com.cryptopilot.trading;

import java.util.UUID;

/**
 * What the {@code trading} module tells other modules about one Trader's plan.
 *
 * <p>Rule: BR-51; TECHNICAL_DESIGN 7.14.
 */
public interface TradingPlanApi {

    /**
     * The Trader's own plan, for the AI Assistant's context snapshot.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the Trader has no such plan (MSG41); a
     *     plan of another Trader is reported the same way
     */
    PlanContext planContext(UUID userId, UUID planId);
}
