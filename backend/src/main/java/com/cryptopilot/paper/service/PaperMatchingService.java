package com.cryptopilot.paper.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The storage side of the paper matching engine: the orders its book starts from, the fills it decides, and how far
 * it has matched.
 *
 * <p>Rule: TR-02; NSF-07; Q-T6.
 */
public interface PaperMatchingService {

    /** Every working LIMIT order. */
    List<RestingOrder> restingOrders();

    /**
     * Fills a working order the price reached, at its own price, as a maker: under its Trader's lock, and only if it
     * still works.
     *
     * @param tradedAt the open time of the candle that reached it (D-78)
     * @return whether this call filled it; {@code false} when it was cancelled or filled first
     */
    boolean fill(RestingOrder order, Instant tradedAt, FillSource source);

    /** The open time of the pair's last closed and fully matched candle, from which a replay starts; or empty. */
    Optional<Instant> watermark(MarketType market, UUID pairId);

    /** Records that the candle opened at {@code openTime} is closed and fully matched; never moves backwards. */
    void advanceWatermark(MarketType market, UUID pairId, Instant openTime);
}
