package com.cryptopilot.market.service;

/**
 * The use case of {@link com.cryptopilot.market.service.impl.CoinDescriptionServiceImpl}: give the coins Binance lists
 * a name and a logo from CoinPaprika (D-48).
 *
 * <p>Rule: SRS 3.1.5; BR-09 (prices and rules stay Binance's; CoinPaprika describes, never trades).
 */
public interface CoinDescriptionService {

    /**
     * Describes the coins still without a logo or still named after their symbol, and never overwrites a name or a
     * logo already set.
     *
     * @return how many coins changed
     * @throws com.cryptopilot.market.client.CoinPaprikaException when the coin list could not be read; nothing has
     *     been written then
     */
    int describeCoins();
}
