package com.cryptopilot.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The warnings of held paper Futures positions (TR-27): a position whose mark comes near its liquidation price, and a
 * position held without a stop loss. The margin ratio has its own margin call (TR-43). Each warning is an in-app
 * notice, sent at most once per cooldown for a position near liquidation and once for a position without a stop loss.
 *
 * <p>Rule: TR-27; D-127.
 */
public interface FuturesPositionWarningService {

    /** The Traders holding an open Futures position. */
    List<UUID> holders();

    /**
     * Looks at the Trader's open positions and warns what is due at {@code at}.
     *
     * @return how many warnings were sent
     */
    int warn(UUID userId, Instant at);
}
