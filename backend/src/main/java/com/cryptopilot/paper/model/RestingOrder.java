package com.cryptopilot.paper.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A working LIMIT order as the paper matching engine tracks it.
 *
 * <p>Rule: TR-02; NSF-07; D-77.
 *
 * @param orderId the order
 * @param accountId its account, whose Trader's lock a fill is made under
 * @param market the market
 * @param pairId the pair
 * @param side BUY waits below the price, SELL above it
 * @param limitPrice the price it waits at
 * @param placedAt when it was placed; a candle that opened before it never fills it (D-77)
 */
public record RestingOrder(
        UUID orderId,
        UUID accountId,
        MarketType market,
        UUID pairId,
        OrderSide side,
        BigDecimal limitPrice,
        Instant placedAt) {

    public RestingOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(placedAt, "placedAt");
    }
}
