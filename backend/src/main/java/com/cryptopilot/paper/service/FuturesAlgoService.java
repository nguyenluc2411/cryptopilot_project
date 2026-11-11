package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.request.FuturesAlgoOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesAlgoOrderResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The paper Futures conditional orders of TR-20, as the exchange's algo order API offers them: place one, cancel one or
 * all of a pair's, and read one, the waiting ones and the history. Each needs the Futures feature of the Trader's plan
 * and an opened paper account.
 *
 * <p>Rule: TR-20; D-127.
 */
public interface FuturesAlgoService {

    /** Places a conditional order. A key already used for an order with the same values returns that order instead. */
    FuturesAlgoOrderResponse place(UUID userId, FuturesAlgoOrderRequest request);

    /** Cancels one waiting conditional order of the pair, named by its id or its client id. */
    FuturesAlgoOrderResponse cancel(UUID userId, String symbol, UUID algoId, String clientAlgoId);

    /** Cancels every waiting conditional order of the pair. */
    List<FuturesAlgoOrderResponse> cancelAll(UUID userId, String symbol);

    /** One conditional order of the pair, named by its id or its client id. */
    FuturesAlgoOrderResponse order(UUID userId, String symbol, UUID algoId, String clientAlgoId);

    /** The waiting conditional orders, of one pair or of all, newest first. */
    List<FuturesAlgoOrderResponse> openOrders(UUID userId, String symbol);

    /**
     * The latest conditional orders of the pair placed in {@code [startTime, endTime)}, oldest first.
     *
     * @param limit how many, 1 to 1000; 500 when absent
     */
    List<FuturesAlgoOrderResponse> allOrders(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit);
}
