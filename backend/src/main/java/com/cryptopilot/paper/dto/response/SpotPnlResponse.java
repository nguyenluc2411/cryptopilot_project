package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Spot wallet's coins with their cost and unrealised PnL (TR-13), largest value first. USDT, the coin everything
 * is valued in, is not listed.
 *
 * @param coins each coin held
 * @param totalValue the sum of the values known
 * @param totalUnrealizedPnl the sum of the unrealised PnL known
 * @param complete whether every coin had a cost and a price; when not, the sums leave some out
 */
public record SpotPnlResponse(
        List<CoinPnlResponse> coins, BigDecimal totalValue, BigDecimal totalUnrealizedPnl, boolean complete) {}
