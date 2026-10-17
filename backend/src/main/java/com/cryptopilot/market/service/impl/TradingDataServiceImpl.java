package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.dto.response.DepthResponse;
import com.cryptopilot.market.dto.response.MarketTradesResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.TradingDataService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves what {@link TradingMarketData} holds for an enabled pair (BR-07): a pair that is disabled, or enabled only on
 * the other market, answers 404 like one that does not exist.
 *
 * <p>Rule: TR-03; BR-07; plan sections 12.3 and 15.
 */
@Service
public class TradingDataServiceImpl implements TradingDataService {

    private final MarketRequests requests;
    private final TradingMarketData data;
    private final int kept;

    public TradingDataServiceImpl(MarketRequests requests, TradingMarketData data, BinanceStreamProperties properties) {
        this.requests = requests;
        this.data = data;
        this.kept = properties.trading().recentTrades();
    }

    @Override
    @Transactional(readOnly = true)
    public DepthResponse depth(String market, String symbol) {
        MarketType type = MarketRequests.market(market);
        CryptoPair pair = requests.enabledPair(type, symbol);
        return data.currentDepth(type, pair.getId())
                .map(depth -> TradingResponses.depth(type.name(), pair.getSymbol(), depth))
                .orElseGet(() -> TradingResponses.noDepth(type.name(), pair.getSymbol()));
    }

    @Override
    @Transactional(readOnly = true)
    public MarketTradesResponse trades(String market, String symbol, Integer limit) {
        MarketType type = MarketRequests.market(market);
        int count = limit == null ? kept : limit;
        if (count < 1 || count > kept) {
            throw MarketRequests.invalid("limit must be between 1 and " + kept + ", was " + count);
        }
        CryptoPair pair = requests.enabledPair(type, symbol);
        return new MarketTradesResponse(
                type.name(),
                pair.getSymbol(),
                data.recentTrades(type, pair.getId(), count).stream()
                        .map(TradingResponses::trade)
                        .toList());
    }
}
