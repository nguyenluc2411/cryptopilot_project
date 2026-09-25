package com.cryptopilot.market.service;

import com.cryptopilot.market.model.SnapshotRun;

/**
 * The use cases of {@link com.cryptopilot.market.service.impl.MarketSnapshotServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: NSF-03 (PERIODIC snapshots every minute); TECHNICAL_DESIGN 7.1 step 7.
 */
public interface MarketSnapshotService {

    /** Writes this minute's snapshots and answers what was written. */
    SnapshotRun writePeriodic();
}
