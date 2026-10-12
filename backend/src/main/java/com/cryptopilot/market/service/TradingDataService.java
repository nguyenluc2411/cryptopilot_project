package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.DepthResponse;
import com.cryptopilot.market.dto.response.MarketTradesResponse;

/**
 * The first state of the trading screen's order book and trades panels (TR-03), served before the topics take over;
 * implemented by {@link com.cryptopilot.market.service.impl.TradingDataServiceImpl}.
 *
 * <p>Rule: TR-03; BR-07; plan sections 12.3 and 15.
 */
public interface TradingDataService {

    /**
     * The latest depth snapshot of an enabled pair; its levels are empty when no current one is held.
     *
     * @param market spot or futures, in any case
     * @param symbol the pair's symbol, in any case
     */
    DepthResponse depth(String market, String symbol);

    /**
     * The latest trades of an enabled pair, newest first.
     *
     * @param market spot or futures, in any case
     * @param symbol the pair's symbol, in any case
     * @param limit how many, 1 to the number kept; default all kept
     */
    MarketTradesResponse trades(String market, String symbol, Integer limit);
}
