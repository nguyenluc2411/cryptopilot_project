package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.model.PriceLookup;

/**
 * The latest-price cache of T-022; implemented by {@link com.cryptopilot.market.service.impl.PriceCacheServiceImpl}.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6; ADR-005; D-48.
 */
public interface PriceCacheService {

    /**
     * Takes a Spot ticker or a futures mark price from the stream, without any I/O: the value waits in memory for the
     * next flush, and a value older than the one waiting is dropped. Anything else is ignored.
     */
    void record(MarketType market, StreamMessage message);

    /**
     * Writes the waiting values to Redis. A failure is logged and counted, and the values stay waiting for the next
     * flush; nothing is thrown.
     *
     * @return how many values Redis accepted (a value older than the one already cached is not)
     */
    int flush();

    /** The cached price of a pair, with the state that says whether it may be used as current. */
    PriceLookup latest(MarketType market, String symbol);
}
