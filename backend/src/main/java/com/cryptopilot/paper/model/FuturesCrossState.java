package com.cryptopilot.paper.model;

import com.cryptopilot.paper.calculator.FuturesMarginMath;
import java.math.BigDecimal;

/**
 * The cross side of a Futures account at the current mark prices: the wallet balance, what is left of it outside the
 * isolated wallets, the unrealized profit and initial margin of the cross positions, and the margin of the open
 * orders.
 *
 * <p>Rule: TR-14, TR-18; D-127.
 *
 * @param walletBalance the Futures wallet balance, the sum of its ledger; may be negative
 * @param crossWalletBalance the wallet balance less every isolated wallet
 * @param crossUnrealizedPnl the unrealized profit of the cross positions at the mark
 * @param crossInitialMargin the initial margin of the cross positions at the mark
 * @param openOrderInitialMargin the margin of the open orders
 */
public record FuturesCrossState(
        BigDecimal walletBalance,
        BigDecimal crossWalletBalance,
        BigDecimal crossUnrealizedPnl,
        BigDecimal crossInitialMargin,
        BigDecimal openOrderInitialMargin) {

    /** The derived balances: available, crossFreeCash and the most that can be withdrawn. */
    public FuturesAccountMargin margin() {
        return FuturesMarginMath.accountMargin(
                crossWalletBalance, crossUnrealizedPnl, crossInitialMargin, openOrderInitialMargin);
    }
}
