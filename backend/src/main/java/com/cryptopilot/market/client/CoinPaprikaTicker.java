package com.cryptopilot.market.client;

import java.math.BigDecimal;

/**
 * One coin of CoinPaprika's {@code /tickers}: what is needed to put a market cap on a coin Binance prices.
 *
 * @param id CoinPaprika's id, e.g. {@code btc-bitcoin}
 * @param symbol the ticker, upper case, e.g. {@code BTC}
 * @param rank the rank by market cap, 1 the largest
 * @param circulatingSupply the coins in circulation, or {@code null} when CoinPaprika does not know
 */
public record CoinPaprikaTicker(String id, String symbol, int rank, BigDecimal circulatingSupply) {}
