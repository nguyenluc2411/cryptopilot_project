package com.cryptopilot.market.model.enums;

/**
 * How current the exchange data of one market is, and so whether that market may be traded (D-158). Each market has
 * its own, as Spot and Futures come from different streams.
 *
 * <p>Rule: NSF-03; D-158.
 */
public enum MarketHealthState {

    /** Data flows; trading is open. */
    LIVE,

    /** The data stopped a short while ago and the stream is reconnecting; trading stays open under the stale rules. */
    RECONNECTING,

    /** The data has been lost for longer than the pause threshold; trading is closed. */
    PAUSED,

    /** The data flows again and what was missed is being replayed; trading stays closed until that is done. */
    RECOVERING,

    /** An administrator closed trading on purpose; it reopens through {@link #RECOVERING}. */
    MAINTENANCE;

    /** Whether orders, positions and wallets of the market may be changed in this state. */
    public boolean tradingOpen() {
        return this == LIVE || this == RECONNECTING;
    }
}
