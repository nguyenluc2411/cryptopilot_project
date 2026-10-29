package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesTradeResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The paper Futures orders of TR-15 and the fills of TR-16, as the exchange's USDⓈ-M API offers them: place a LIMIT or
 * MARKET order in one-way mode, cancel one or all of a pair's, and read an order, the open orders, the order history
 * and the trades. Each needs the Futures feature of the Trader's plan and an opened paper account.
 *
 * <p>Rule: TR-15, TR-16; D-127.
 */
public interface FuturesOrderService {

    /**
     * Places an order. A key already used for an order with the same values returns that order instead.
     *
     * @return the order after placement: executed on arrival as far as the book allowed, waiting, or ended
     */
    FuturesOrderResponse place(UUID userId, FuturesOrderRequest request);

    /** Cancels one working order of the pair, named by its id or its client order id. */
    FuturesOrderResponse cancel(UUID userId, String symbol, UUID orderId, String origClientOrderId);

    /** Cancels every working order of the pair. */
    List<FuturesOrderResponse> cancelAll(UUID userId, String symbol);

    /** One order of the pair, named by its id or its client order id. */
    FuturesOrderResponse order(UUID userId, String symbol, UUID orderId, String origClientOrderId);

    /** The working orders, of one pair or of all, newest first. */
    List<FuturesOrderResponse> openOrders(UUID userId, String symbol);

    /**
     * The latest orders of the pair placed in {@code [startTime, endTime)}, oldest first.
     *
     * @param limit how many, 1 to 1000; 500 when absent
     */
    List<FuturesOrderResponse> allOrders(UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit);

    /**
     * The liquidation orders of the Trader, of one pair or of all, placed in {@code [startTime, endTime)}, newest
     * first (TR-22). Binance: {@code GET /fapi/v1/forceOrders}.
     *
     * @param limit how many, 1 to 100; 50 when absent
     */
    List<FuturesOrderResponse> forceOrders(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit);

    /**
     * The latest fills of the pair traded in {@code [startTime, endTime)}, oldest first.
     *
     * @param limit how many, 1 to 1000; 500 when absent
     */
    List<FuturesTradeResponse> userTrades(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit);
}
