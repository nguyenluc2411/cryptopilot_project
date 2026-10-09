package com.cryptopilot.paper.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One execution of a paper order of the caller: a row of the trade history.
 *
 * @param fillId the fill
 * @param orderId the order it executed
 * @param pairId the pair
 * @param symbol the pair's symbol
 * @param market the market
 * @param side BUY or SELL
 * @param price the execution price
 * @param quantity the base quantity
 * @param quoteAmount what it cost or brought in the quote asset, before the fee
 * @param fee the commission
 * @param feeAsset the coin the commission was taken in: the one the fill brought in
 * @param liquidity MAKER for an order that waited in the book, TAKER for one that executed on arrival
 * @param source LIVE, or REPLAY when decided by candles replayed after a restart (Q-T6)
 * @param tradedAt when it happened
 */
public record FillResponse(
        UUID fillId,
        UUID orderId,
        UUID pairId,
        String symbol,
        MarketType market,
        OrderSide side,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteAmount,
        BigDecimal fee,
        String feeAsset,
        Liquidity liquidity,
        FillSource source,
        Instant tradedAt) {}
