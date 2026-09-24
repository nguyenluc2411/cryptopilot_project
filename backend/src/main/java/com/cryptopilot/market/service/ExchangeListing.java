package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;

/**
 * What the exchange says about one symbol on one market, in the system's own terms: which coins it is
 * made of, whether it is trading, and its filters. The input of NSF-01's synchronisation (T-019), which
 * matches it to a {@code CryptoPair} by symbol and applies the filters to that market.
 *
 * <p>Rule: NSF-01; BR-07.
 *
 * @param market the market the exchange listed it on
 * @param symbol the pair symbol, e.g. {@code BTCUSDT}
 * @param baseAsset the coin bought and sold, e.g. {@code BTC}
 * @param quoteAsset the coin it is priced in, e.g. {@code USDT}
 * @param trading whether the exchange trades it now; any status but {@code TRADING} is not trading, and
 *     NSF-01 flags such a pair for an administrator
 * @param filters the trading rules of this market
 */
public record ExchangeListing(
        MarketType market, String symbol, String baseAsset, String quoteAsset, boolean trading, PairFilters filters) {}
