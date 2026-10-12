package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The top levels of a pair's order book: the payload of {@code /topic/depth.{market}.{symbol}} and of
 * {@code GET /api/v1/market/{market}/{symbol}/depth}. Each level is {@code [price, quantity]}, decimals as strings, as
 * Binance sends them. The levels are empty and {@code lastUpdateId} absent when no current depth is held (a pair not
 * streamed with the trading streams, or a lost stream).
 *
 * <p>Rule: TR-03; plan sections 5 and 15.
 *
 * @param market {@code SPOT} or {@code FUTURES}
 * @param symbol the symbol
 * @param lastUpdateId the order book update id of the snapshot; a client joins the topic to it
 * @param receivedAt when this server read the snapshot
 * @param bids the bids, highest price first
 * @param asks the asks, lowest price first
 */
public record DepthResponse(
        String market,
        String symbol,
        Long lastUpdateId,
        Instant receivedAt,
        List<List<BigDecimal>> bids,
        List<List<BigDecimal>> asks) {

    public DepthResponse {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
