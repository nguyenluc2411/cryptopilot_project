package com.cryptopilot.trading.service;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.List;
import java.util.UUID;

/**
 * Who the notices of a market incident reach among the plans' Traders (D-158).
 *
 * <p>Rule: NSF-03; D-158.
 */
public interface PlanIncidentService {

    /** The Traders with an ACTIVE plan on the market, or an executed one whose simulated position is open. */
    List<UUID> tradersOn(MarketType market);
}
