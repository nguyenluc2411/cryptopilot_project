package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import java.util.List;

/**
 * The use cases of {@link com.cryptopilot.market.service.impl.DemoPairActivationImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: BR-07; NSF-01; Q-16.
 */
public interface DemoPairActivation {

    /**
     * Enables these symbols on this market where the exchange trades them.
     *
     * @return the symbols newly enabled, in the order given
     */
    List<String> activate(MarketType market, List<String> symbols);
}
