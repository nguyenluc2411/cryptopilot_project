package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.request.MaintenanceRequest;
import com.cryptopilot.market.dto.request.ResumeRequest;
import com.cryptopilot.market.dto.response.MarketIncidentResponse;
import com.cryptopilot.market.dto.response.MarketStatusResponse;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.List;
import java.util.UUID;

/**
 * What the Traders and the administration console read of the markets' data health (D-159), and the maintenance
 * switch.
 *
 * <p>Rule: NSF-03; D-159.
 */
public interface MarketStatusService {

    /** Every market's data health now. */
    MarketStatusResponse status();

    /** The latest incidents of a market, newest first; at most {@code limit}, 1 to 100. */
    List<MarketIncidentResponse> incidents(MarketType market, int limit);

    /** Starts or ends maintenance of a market, audited as the administrator's change; returns the status after. */
    MarketStatusResponse maintenance(UUID adminId, MaintenanceRequest request);

    /**
     * Reopens a recovering market by an administrator's decision (D-159), audited; refused with {@code DATA_CONFLICT}
     * unless it is recovering.
     */
    MarketStatusResponse resume(UUID adminId, ResumeRequest request);
}
