package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;

/**
 * The market topics of T-022 (TECHNICAL_DESIGN 9); implemented by
 * {@link com.cryptopilot.market.service.impl.MarketBroadcastServiceImpl}.
 *
 * <p>Rule: NSF-03; SRS 4.2.3; TECHNICAL_DESIGN 9; D-48, D-50.
 */
public interface MarketBroadcastService {

    /**
     * Takes a stream message, without any I/O: its update waits for the next push of its destination, and replaces an
     * older update waiting there.
     */
    void publish(MarketType market, StreamMessage message);

    /**
     * Pushes every ticker and kline destination that has an update waiting, each once.
     *
     * @return how many messages were handed to the broker
     */
    int pushUpdates();

    /**
     * Pushes the overview of each market that has streamed a ticker.
     *
     * @return how many overviews were handed to the broker
     */
    int pushOverviews();
}
