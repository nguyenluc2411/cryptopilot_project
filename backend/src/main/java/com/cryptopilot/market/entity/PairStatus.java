package com.cryptopilot.market.entity;

/**
 * Whether an administrator has made a pair available (BR-07, SCR-37). The constants are the values of the
 * {@code ck_crypto_pair_status} check constraint, spelled the same way.
 *
 * <p>This is the administrator's switch, not the exchange's trading status: a pair the exchange has
 * stopped trading is flagged for an administrator (NSF-01), who then decides.
 */
public enum PairStatus {

    /** Shown, collected and available for alerts and plans on the markets enabled for it. */
    ACTIVE,

    /** Neither shown nor collected, whatever its market switches say. */
    INACTIVE
}
