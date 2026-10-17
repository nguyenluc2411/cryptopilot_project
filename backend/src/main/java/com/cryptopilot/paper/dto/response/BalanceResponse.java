package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One coin of a paper wallet.
 *
 * @param coinId the coin
 * @param asset its symbol, e.g. {@code BTC}
 * @param free the amount free to trade or transfer
 * @param locked the amount held by open orders
 * @param total free plus locked
 * @param usdtValue the total valued at the current Spot last price, or {@code null} when no current price is known
 */
public record BalanceResponse(
        UUID coinId, String asset, BigDecimal free, BigDecimal locked, BigDecimal total, BigDecimal usdtValue) {}
