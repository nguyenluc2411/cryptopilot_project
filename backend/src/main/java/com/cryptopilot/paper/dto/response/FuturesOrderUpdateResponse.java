package com.cryptopilot.paper.dto.response;

import java.util.List;

/**
 * A paper Futures order as a change left it, with the fills that change made, pushed on
 * {@code /user/queue/futures.orders} (TR-41). Binance: the {@code ORDER_TRADE_UPDATE} event of the user data stream,
 * which carries the order and its last trade; here every fill of the change is listed, a reversal being two.
 *
 * @param order the order afterwards
 * @param trades the fills the change made, oldest first; empty for a placement, cancel or expiry without one
 */
public record FuturesOrderUpdateResponse(FuturesOrderResponse order, List<FuturesTradeResponse> trades) {}
