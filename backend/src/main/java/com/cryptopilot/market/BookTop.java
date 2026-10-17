package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The best bid and ask of a pair, from the {@code <symbol>@bookTicker} stream (TR-03). A MARKET order with no depth
 * at hand fills at it, and a LIMIT order that reaches it at placement is marketable (plan section 11.2).
 *
 * <p>Rule: TR-03, TR-06; plan section 11.1.
 *
 * @param market the market
 * @param pairId the pair
 * @param updateId the order book update id the values belong to
 * @param bidPrice the best bid
 * @param bidQuantity the quantity at the best bid
 * @param askPrice the best ask
 * @param askQuantity the quantity at the best ask
 * @param receivedAt when this server read it; the Spot stream carries no event time
 */
public record BookTop(
        MarketType market,
        UUID pairId,
        long updateId,
        BigDecimal bidPrice,
        BigDecimal bidQuantity,
        BigDecimal askPrice,
        BigDecimal askQuantity,
        Instant receivedAt) {

    public BookTop {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(bidPrice, "bidPrice");
        Objects.requireNonNull(bidQuantity, "bidQuantity");
        Objects.requireNonNull(askPrice, "askPrice");
        Objects.requireNonNull(askQuantity, "askQuantity");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
