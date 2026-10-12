package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One market trade of the trades panel (plan section 5): Binance's aggregated trade. Decimals are strings.
 *
 * <p>Rule: TR-03; plan sections 5 and 15.
 *
 * @param id the exchange's id of the aggregated trade, increasing per pair
 * @param price the traded price
 * @param quantity the traded base quantity
 * @param time when the trade happened
 * @param buyerMaker whether the buyer was the maker: the taker sold, drawn red
 */
public record MarketTradeResponse(long id, BigDecimal price, BigDecimal quantity, Instant time, boolean buyerMaker) {}
