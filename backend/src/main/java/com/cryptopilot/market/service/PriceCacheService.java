package com.cryptopilot.market.service;

import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The latest-price cache of T-022; implemented by {@link com.cryptopilot.market.service.impl.PriceCacheServiceImpl}.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6; ADR-005; D-48.
 */
public interface PriceCacheService {

    /**
     * Takes a Spot ticker, a futures mark price or a futures 1-minute candle update from the stream, without any I/O:
     * the value waits in memory for the next flush, and a value older than the one waiting is dropped. The close of a
     * futures 1-minute candle update is the futures last price, kept with its own instant (D-111 E2/E3). Anything else
     * is ignored.
     */
    void record(MarketType market, StreamMessage message);

    /**
     * Writes the waiting values to Redis. A failure is logged and counted, and the values stay waiting for the next
     * flush; nothing is thrown.
     *
     * @return how many values Redis accepted (a value older than the one already cached is not)
     */
    int flush();

    /**
     * The cached price of a pair, with the state that says whether it may be used as current. A futures entry is its
     * mark price group; its last price, which has an instant of its own, is read by {@link #latestLast}.
     */
    PriceLookup latest(MarketType market, String symbol);

    /**
     * The last trade price of a pair, with the state of its own instant: the ticker's on Spot (as {@link #latest}),
     * the close of the latest 1-minute candle update on futures. Only {@code lastPrice} and {@code sourceTime} of a
     * futures answer are set.
     *
     * <p>Rule: BR-33; D-111 E2/E3.
     */
    PriceLookup latestLast(MarketType market, String symbol);

    /**
     * The cached prices of several pairs of one market. Every symbol asked for has an entry, with the same states as
     * {@link #latest(MarketType, String)}. This default reads one pair at a time; the Redis implementation reads them
     * all in one round trip.
     */
    default Map<String, PriceLookup> latest(MarketType market, Collection<String> symbols) {
        Map<String, PriceLookup> found = new LinkedHashMap<>();
        for (String symbol : symbols) {
            found.putIfAbsent(symbol, latest(market, symbol));
        }
        return found;
    }
}
