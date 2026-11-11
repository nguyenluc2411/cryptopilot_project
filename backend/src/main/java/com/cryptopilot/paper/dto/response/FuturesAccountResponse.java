package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The paper Futures account at the current mark prices, with the exchange's field names. Binance:
 * {@code GET /fapi/v3/account}. One asset, USDT; {@code marginRatio} is CryptoPilot's addition (plan 4.10).
 *
 * @param totalInitialMargin the positions' and open orders' initial margin
 * @param totalMaintMargin the positions' maintenance margin
 * @param totalWalletBalance the Futures wallet, the sum of its ledger; below zero only while cross positions are open
 * @param totalUnrealizedProfit every position's unrealized profit
 * @param totalMarginBalance the wallet plus the unrealized profit
 * @param totalPositionInitialMargin the positions' initial margin at the mark
 * @param totalOpenOrderInitialMargin the open orders' margin
 * @param totalCrossWalletBalance the wallet less every isolated wallet
 * @param totalCrossUnPnl the cross positions' unrealized profit
 * @param availableBalance what a cross order can use, never below zero
 * @param maxWithdrawAmount what can be transferred out, never below zero
 * @param marginRatio the cross side's maintenance over its margin balance; {@code null} when that balance is zero or
 *     below
 * @param assets the one asset, USDT
 * @param positions the open positions
 */
public record FuturesAccountResponse(
        BigDecimal totalInitialMargin,
        BigDecimal totalMaintMargin,
        BigDecimal totalWalletBalance,
        BigDecimal totalUnrealizedProfit,
        BigDecimal totalMarginBalance,
        BigDecimal totalPositionInitialMargin,
        BigDecimal totalOpenOrderInitialMargin,
        BigDecimal totalCrossWalletBalance,
        BigDecimal totalCrossUnPnl,
        BigDecimal availableBalance,
        BigDecimal maxWithdrawAmount,
        BigDecimal marginRatio,
        List<Asset> assets,
        List<Position> positions) {

    /** The account's figures in one asset. Binance: an item of {@code assets}. */
    public record Asset(
            String asset,
            BigDecimal walletBalance,
            BigDecimal unrealizedProfit,
            BigDecimal marginBalance,
            BigDecimal maintMargin,
            BigDecimal initialMargin,
            BigDecimal positionInitialMargin,
            BigDecimal openOrderInitialMargin,
            BigDecimal crossWalletBalance,
            BigDecimal crossUnPnl,
            BigDecimal availableBalance,
            BigDecimal maxWithdrawAmount,
            Instant updateTime) {}

    /** One open position in short. Binance: an item of {@code positions}. */
    public record Position(
            String symbol,
            PositionSide positionSide,
            BigDecimal positionAmt,
            BigDecimal unrealizedProfit,
            BigDecimal isolatedMargin,
            BigDecimal notional,
            BigDecimal isolatedWallet,
            BigDecimal initialMargin,
            BigDecimal maintMargin,
            Instant updateTime) {}
}
