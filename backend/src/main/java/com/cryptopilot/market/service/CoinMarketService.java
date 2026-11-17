package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.CoinMarketResponse;

/**
 * The use case of {@link com.cryptopilot.market.service.impl.CoinMarketServiceImpl}: the market list of coins with the
 * figures of the whole market (D-48).
 *
 * <p>Rule: SRS 3.3.1; UC-09.
 */
public interface CoinMarketService {

    /** The listed coins and the market figures; computed at most once a minute and shared by every caller. */
    CoinMarketResponse coins();
}
