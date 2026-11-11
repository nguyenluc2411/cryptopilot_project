package com.cryptopilot.paper.model;

import java.math.BigDecimal;

/**
 * How a profit-or-loss event of an isolated position moves money: what the isolated wallet gains or loses, what the
 * cross wallet gains, and the insurance cover that keeps the isolated wallet from going below zero. In every branch
 * {@code crossChange = walletChange − isolatedWalletChange}, and {@code crossChange} is never negative.
 *
 * <p>Rule: TR-16, TR-22, TR-24; D-127.
 *
 * @param branch the branch of the rule the event fell into
 * @param isolatedWalletChange the signed change of the isolated wallet
 * @param crossChange the change of the cross wallet, zero or positive
 * @param insuranceCover the loss beyond the isolated wallet, zero or positive, recorded as its own ledger entry
 */
public record IsolatedCashFlow(
        Branch branch, BigDecimal isolatedWalletChange, BigDecimal crossChange, BigDecimal insuranceCover) {

    /** The change of the Futures wallet balance: the event's own amount plus the insurance cover. */
    public BigDecimal walletChange() {
        return isolatedWalletChange.add(crossChange);
    }

    /** The branches of the isolated cash-flow rule. */
    public enum Branch {

        /** The released margin covers the result: the wallet keeps the rest and the cross wallet gets it. */
        RELEASE,

        /** The loss is more than the released margin and is taken from what is left of the isolated wallet. */
        LOSS_ABSORB,

        /** The loss is more than the whole isolated wallet; the excess is an insurance cover. */
        INSURANCE
    }
}
