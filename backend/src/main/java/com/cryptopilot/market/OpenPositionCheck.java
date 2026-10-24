package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;

/**
 * Tells whether simulated positions are open on a pair's market, so that the administration of SCR-37 can refuse to
 * disable it (BR-59, MSG40). The positions belong to the modules that open them — the plans' journal and the demo
 * trading account — and both depend on this module, so each implements this interface as a bean and the pair
 * administration asks every one of them; the dependency points from them to here, never back.
 *
 * <p>Rule: BR-59; UC-45.
 */
public interface OpenPositionCheck {

    /** Whether at least one simulated position is open on this pair's market. */
    boolean anyOpen(UUID pairId, MarketType market);
}
