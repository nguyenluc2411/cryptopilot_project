package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;

/**
 * Where the stream hands every message besides storage: the latest-price cache and the market topics (T-022);
 * implemented by {@link com.cryptopilot.market.service.impl.MarketUpdateServiceImpl}.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6 and 9; D-48.
 */
public interface MarketUpdateService {

    /** Takes a message on the stream reader's thread: no I/O, and never a thrown exception. */
    void onUpdate(MarketType market, StreamMessage message);
}
