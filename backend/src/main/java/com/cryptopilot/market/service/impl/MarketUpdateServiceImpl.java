package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.service.MarketBroadcastService;
import com.cryptopilot.market.service.MarketUpdateService;
import com.cryptopilot.market.service.PriceCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hands each stream message to the latest-price cache and to the market topics, each on its own: a failure of one is
 * logged and reaches neither the other nor the stream, so NSF-03 stores its candles and snapshots whatever happens here.
 * Both only record the message in memory; their I/O runs later, on their own schedule.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6 and 9; ADR-005.
 */
@Service
public class MarketUpdateServiceImpl implements MarketUpdateService {

    private static final Logger log = LoggerFactory.getLogger(MarketUpdateService.class);

    private final PriceCacheService cache;
    private final MarketBroadcastService broadcast;

    public MarketUpdateServiceImpl(PriceCacheService cache, MarketBroadcastService broadcast) {
        this.cache = cache;
        this.broadcast = broadcast;
    }

    @Override
    public void onUpdate(MarketType market, StreamMessage message) {
        try {
            cache.record(market, message);
        } catch (RuntimeException failure) {
            log.warn("Latest-price cache refused a {} message: {}", market, failure.toString());
        }
        try {
            broadcast.publish(market, message);
        } catch (RuntimeException failure) {
            log.warn("Market topics refused a {} message: {}", market, failure.toString());
        }
    }
}
