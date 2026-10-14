package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.response.EquityResponse;
import com.cryptopilot.paper.dto.response.SpotPnlResponse;
import java.util.UUID;

/**
 * What a paper account's holdings cost and are worth (TR-13): the average cost and unrealised PnL of each Spot coin,
 * the account's worth now against the start of the day, and the daily snapshots of that worth.
 *
 * <p>Rule: TR-13.
 */
public interface PaperPnlService {

    SpotPnlResponse spotPnl(UUID userId);

    /** @param days how many days of snapshots back from today, 1 to 365; 30 when absent */
    EquityResponse equity(UUID userId, Integer days);

    /** Takes today's snapshot of every account that has none yet; how many it took. */
    int snapshotAll();
}
