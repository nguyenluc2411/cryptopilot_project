package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketHealth;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;

/**
 * The data health of each market inside the {@code market} module (D-158): what {@link MarketHealth} tells the other
 * modules, plus the maintenance switch of the administration console.
 *
 * <p>Rule: NSF-03; D-158.
 */
public interface MarketHealthService extends MarketHealth {

    /** Closes trading on the market on purpose, with an optional message for the Traders. */
    void startMaintenance(MarketType market, String message, UUID by);

    /** Ends maintenance; the market reopens through recovery, never straight away while its data is lost. */
    void endMaintenance(MarketType market);
}
