package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesOrderWarningsResponse;
import java.util.UUID;

/**
 * The risk warnings of the paper Futures order form (TR-26): what an order would cost and risk, checked against the
 * Trader's risk profile and the system's warning thresholds, before it is placed. The exchange has no such endpoint;
 * the form calls it as the Trader types. Needs the Futures feature of the Trader's plan and an opened paper account.
 *
 * <p>Rule: TR-26; BR-28, BR-29; D-127.
 */
public interface FuturesOrderWarningService {

    /** The warnings of the order the request would place, which is neither stored nor placed. */
    FuturesOrderWarningsResponse warnings(UUID userId, FuturesOrderRequest request);
}
