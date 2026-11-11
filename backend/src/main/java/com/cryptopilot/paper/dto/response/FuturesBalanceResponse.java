package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The paper Futures balance of one asset, in short. Binance: an item of {@code GET /fapi/v3/balance}.
 *
 * @param accountAlias always {@code paper}
 * @param asset USDT
 * @param balance the wallet balance
 * @param crossWalletBalance the wallet less every isolated wallet
 * @param crossUnPnl the cross positions' unrealized profit
 * @param availableBalance what a cross order can use
 * @param maxWithdrawAmount what can be transferred out
 * @param marginAvailable always true: the asset may be used as margin
 * @param updateTime when the figures were taken
 */
public record FuturesBalanceResponse(
        String accountAlias,
        String asset,
        BigDecimal balance,
        BigDecimal crossWalletBalance,
        BigDecimal crossUnPnl,
        BigDecimal availableBalance,
        BigDecimal maxWithdrawAmount,
        boolean marginAvailable,
        Instant updateTime) {}
