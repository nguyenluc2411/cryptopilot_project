package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.model.SyncReport;

/**
 * The use cases of {@link com.cryptopilot.market.service.impl.SymbolSyncServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: NSF-01; BR-07, BR-09; TECHNICAL_DESIGN 7.1 and 7.1.2.
 */
public interface SymbolSyncService {

    /**
     * Synchronises one market and reports what changed.
     *
     * @throws BinanceClientException when the exchange refuses; nothing has been written then
     */
    SyncReport sync(MarketType market);
}
