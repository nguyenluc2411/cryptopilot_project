package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The top levels of a pair's order book, from the {@code <symbol>@depth20@100ms} stream (TR-03). A MARKET order walks
 * it for its average price (Q-T7), and the order book panel draws it.
 *
 * <p>Rule: TR-03, TR-06; Q-T7; plan section 11.1.
 *
 * @param market the market
 * @param pairId the pair
 * @param lastUpdateId the order book update id of the snapshot
 * @param bids the bids, highest price first
 * @param asks the asks, lowest price first
 * @param receivedAt when this server read it; the Spot stream carries no event time
 */
public record OrderBookDepth(
        MarketType market,
        UUID pairId,
        long lastUpdateId,
        List<PriceLevel> bids,
        List<PriceLevel> asks,
        Instant receivedAt) {

    public OrderBookDepth {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /**
     * One level of the book.
     *
     * @param price the level's price
     * @param quantity the base quantity resting at it
     */
    public record PriceLevel(BigDecimal price, BigDecimal quantity) {

        public PriceLevel {
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(quantity, "quantity");
        }
    }
}
