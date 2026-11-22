package com.cryptopilot.market.service;

import java.time.Instant;
import java.util.Optional;

/**
 * The use case of {@link com.cryptopilot.market.service.impl.MarketReferenceServiceImpl}: keep the reference figures of
 * the market list that CoinPaprika gives fresh (D-48).
 *
 * <p>Rule: SRS 3.3.1.
 */
public interface MarketReferenceService {

    /**
     * Reads the whole market's cap, and the listed coins' supplies when the last read of them is older than an hour or
     * there was none. A failed read keeps the figures of the last good one and is logged, never thrown.
     */
    void refresh();

    /**
     * Reads the listed coins' supplies now, however recent the last read: a coin just listed has none until then.
     * A failed read keeps the last good supplies and is logged, never thrown.
     */
    void refreshSupplies();

    /** When the supplies were last read, if they ever were. */
    Optional<Instant> suppliesReadAt();
}
