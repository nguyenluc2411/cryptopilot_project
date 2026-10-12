package com.cryptopilot.market.dto.response;

import java.util.List;

/**
 * Trades of a pair: the payload of {@code /topic/trades.{market}.{symbol}} (the trades streamed since the previous
 * push, oldest first) and of {@code GET /api/v1/market/{market}/{symbol}/trades} (the latest ones, newest first).
 *
 * <p>Rule: TR-03; plan sections 5 and 15.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param symbol the symbol
 * @param trades the trades
 */
public record MarketTradesResponse(String market, String symbol, List<MarketTradeResponse> trades) {

    public MarketTradesResponse {
        trades = List.copyOf(trades);
    }
}
