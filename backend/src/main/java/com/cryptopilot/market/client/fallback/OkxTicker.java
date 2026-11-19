package com.cryptopilot.market.client.fallback;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One OKX ticker, as {@code /api/v5/market/tickers} gives it (D-158): display only.
 *
 * @param instrumentId the OKX instrument, e.g. {@code BTC-USDT} or {@code BTC-USDT-SWAP}
 * @param lastPrice the last traded price
 * @param open24h the price 24 hours ago
 * @param high24h the highest price of the last 24 hours
 * @param low24h the lowest price of the last 24 hours
 * @param volume24h the base volume of the last 24 hours
 * @param at when OKX produced it
 */
public record OkxTicker(
        String instrumentId,
        BigDecimal lastPrice,
        BigDecimal open24h,
        BigDecimal high24h,
        BigDecimal low24h,
        BigDecimal volume24h,
        Instant at) {}
