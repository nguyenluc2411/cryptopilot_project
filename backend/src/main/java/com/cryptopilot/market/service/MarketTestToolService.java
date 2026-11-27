package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.request.ReplayStartRequest;
import com.cryptopilot.market.dto.request.ReplayStopRequest;
import com.cryptopilot.market.dto.request.StreamFaultRequest;
import com.cryptopilot.market.dto.response.ReplayResponse;
import com.cryptopilot.market.dto.response.ReplayScenarioResponse;
import com.cryptopilot.market.dto.response.StreamFaultResponse;
import java.util.List;
import java.util.UUID;

/**
 * The market tools of a test environment, committed at Review 2 (BM04 section 9) for the demonstrations and the
 * reliability measures:
 *
 * <ul>
 *   <li>RF-07, the price replay: a pair's prices follow a scripted path from {@code market/replay} instead of the
 *       exchange's, so a liquidation (DS-03a, DS-03b) or an alert (DS-06a) happens when the script says;
 *   <li>RF-12, the fault switch: a market's exchange streams are cut for a while, as a network loss would, so the
 *       reconnection and the alerts after it can be measured (NFR-06, DS-06b).
 * </ul>
 *
 * <p>Every action is audited as the administrator's change of a system setting.
 */
public interface MarketTestToolService {

    /** Cuts the market's streams for the requested outage. */
    StreamFaultResponse cutStreams(UUID adminId, StreamFaultRequest request);

    /** The scenarios the environment can replay, by name. */
    List<ReplayScenarioResponse> scenarios();

    /** The replays running now. */
    List<ReplayResponse> replays();

    /**
     * Starts a scenario on its pair, replacing the replay running there.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no scenario has that name
     * @throws com.cryptopilot.common.exception.BusinessException DATA_CONFLICT when the market does not stream the
     *     scenario's pair now
     */
    ReplayResponse startReplay(UUID adminId, ReplayStartRequest request);

    /**
     * Stops the replay of a pair.
     *
     * @throws com.cryptopilot.common.exception.BusinessException DATA_CONFLICT when none is running there
     */
    void stopReplay(UUID adminId, ReplayStopRequest request);
}
