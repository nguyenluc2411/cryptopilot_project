package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One aggregated trade of a pair, from the {@code <symbol>@aggTrade} stream: the trades of one taker order at one
 * price. The paper engine fills resting orders on it, and the trading screen lists it (TR-03).
 *
 * <p>Rule: TR-03, TR-05; plan section 11.1.
 *
 * @param market the market
 * @param pairId the pair
 * @param aggregateTradeId the exchange's id of the aggregated trade, increasing per pair
 * @param price the traded price
 * @param quantity the traded base quantity
 * @param tradeTime when the trade happened (UTC)
 * @param buyerMaker whether the buyer was the maker, so the taker sold
 * @param eventTime when the exchange produced the message
 */
public record MarketTrade(
        MarketType market,
        UUID pairId,
        long aggregateTradeId,
        BigDecimal price,
        BigDecimal quantity,
        Instant tradeTime,
        boolean buyerMaker,
        Instant eventTime) {

    public MarketTrade {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(tradeTime, "tradeTime");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
