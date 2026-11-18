package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.CoinMarketResponse;

/**
 * The use case of {@link com.cryptopilot.market.service.impl.CoinMarketServiceImpl}: the market list of coins with the
 * figures of the whole market (D-48).
 *
 * <p>Rule: SRS 3.3.1; UC-09.
 */
public interface CoinMarketService {

    /**
     * The latest built list; built on the spot before any build, and, with the job off, once it is older than the
     * refresh interval.
     */
    CoinMarketResponse coins();

    /** Builds the list again; a failure keeps the previous one. A refresh already running is not doubled. */
    void refresh();
}
