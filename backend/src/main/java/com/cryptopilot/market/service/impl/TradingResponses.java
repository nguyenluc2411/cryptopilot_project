package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.client.StreamMessage.AggTradeMessage;
import com.cryptopilot.market.client.StreamMessage.DepthMessage;
import com.cryptopilot.market.client.StreamMessage.Level;
import com.cryptopilot.market.dto.response.DepthResponse;
import com.cryptopilot.market.dto.response.MarketTradeResponse;
import java.math.BigDecimal;
import java.util.List;

/**
 * The trades and depth payloads of TR-03, built the same way for the topics (from stream messages) and for the REST
 * snapshots (from what {@link TradingMarketData} holds).
 *
 * <p>Rule: TR-03; plan section 15.
 */
final class TradingResponses {

    private TradingResponses() {}

    static MarketTradeResponse trade(AggTradeMessage message) {
        return new MarketTradeResponse(
                message.aggregateTradeId(),
                message.price(),
                message.quantity(),
                message.tradeTime(),
                message.buyerMaker());
    }

    static MarketTradeResponse trade(MarketTrade trade) {
        return new MarketTradeResponse(
                trade.aggregateTradeId(), trade.price(), trade.quantity(), trade.tradeTime(), trade.buyerMaker());
    }

    static DepthResponse depth(String market, DepthMessage message) {
        return new DepthResponse(
                market,
                message.symbol(),
                message.lastUpdateId(),
                message.eventTime(),
                streamLevels(message.bids()),
                streamLevels(message.asks()));
    }

    static DepthResponse depth(String market, String symbol, OrderBookDepth depth) {
        return new DepthResponse(
                market, symbol, depth.lastUpdateId(), depth.receivedAt(), levels(depth.bids()), levels(depth.asks()));
    }

    static DepthResponse noDepth(String market, String symbol) {
        return new DepthResponse(market, symbol, null, null, List.of(), List.of());
    }

    private static List<List<BigDecimal>> streamLevels(List<Level> levels) {
        return levels.stream()
                .map(level -> List.of(level.price(), level.quantity()))
                .toList();
    }

    private static List<List<BigDecimal>> levels(List<OrderBookDepth.PriceLevel> levels) {
        return levels.stream()
                .map(level -> List.of(level.price(), level.quantity()))
                .toList();
    }
}
