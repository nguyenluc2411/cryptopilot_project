package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.request.FuturesClosePositionRequest;
import com.cryptopilot.paper.dto.request.FuturesPositionMarginRequest;
import com.cryptopilot.paper.dto.response.FuturesMarginHistoryResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionMarginResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the Trader does to an open paper Futures position besides trading it (TR-17): move margin into or out of an
 * isolated one, read those changes back, and close a position, or every one, in part or whole. Each needs the
 * Futures feature of the Trader's plan and an opened paper account.
 *
 * <p>Rule: TR-17; D-127.
 */
public interface FuturesPositionService {

    /** Adds margin to an isolated position from the cross wallet, or takes margin out of it back to the cross wallet. */
    FuturesPositionMarginResponse changeMargin(UUID userId, FuturesPositionMarginRequest request);

    /**
     * The margin changes the Trader made on the pair in {@code [startTime, endTime)}, newest first.
     *
     * @param type 1 for additions, 2 for reductions, {@code null} for both
     * @param limit how many, 1 to 1000; 500 when absent
     */
    List<FuturesMarginHistoryResponse> marginHistory(
            UUID userId, String symbol, Integer type, Instant startTime, Instant endTime, Integer limit);

    /** Closes one position, or every one, by reduce-only orders; the orders as placed. */
    List<FuturesOrderResponse> close(UUID userId, FuturesClosePositionRequest request);
}
