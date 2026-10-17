package com.cryptopilot.paper.model.enums;

/**
 * The two wallets of a paper account. The names are the values {@code wallet_type} accepts.
 *
 * <p>Rule: TR-04.
 */
public enum WalletType {

    /** Holds the coins Spot orders buy and sell. */
    SPOT,

    /** Holds the USDT margin of USDⓈ-M Futures. */
    FUTURES
}
