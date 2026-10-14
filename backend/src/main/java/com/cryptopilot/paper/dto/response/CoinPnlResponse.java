package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One coin of the Spot wallet with what it cost and what it is worth (TR-13), as Binance's Spot PnL shows it.
 *
 * @param coinId the coin
 * @param asset its symbol, e.g. {@code BTC}
 * @param quantity what is held, free plus locked
 * @param averageCost the average cost of one unit in USDT; {@code null} when the cost is not known
 * @param costBasis what the holding cost in USDT; {@code null} when not known
 * @param lastPrice the current Spot last price in USDT; {@code null} when not known
 * @param value the holding at that price; {@code null} without a price
 * @param unrealizedPnl the value less the cost; {@code null} without either
 * @param unrealizedPnlPercent that as a percent of the cost, 2 decimals; {@code null} without it or with a zero cost
 */
public record CoinPnlResponse(
        UUID coinId,
        String asset,
        BigDecimal quantity,
        BigDecimal averageCost,
        BigDecimal costBasis,
        BigDecimal lastPrice,
        BigDecimal value,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPercent) {}
