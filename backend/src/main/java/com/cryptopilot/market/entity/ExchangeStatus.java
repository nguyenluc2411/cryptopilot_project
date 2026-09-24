package com.cryptopilot.market.entity;

/**
 * The exchange's trading status of one market of a pair, in the system's own vocabulary (Q-15). The
 * constants are the values of the {@code ck_crypto_pair_*_exchange_status} check constraints.
 *
 * <p>Binance's words — {@code TRADING}, {@code BREAK}, {@code HALT}, {@code END_OF_DAY} and whatever it adds
 * next — are kept verbatim in the raw column beside this one and never become constants here: any of them
 * but {@code TRADING} is {@link #NOT_TRADING}, so a new vendor status needs no migration and no release.
 *
 * <p>Rule: NSF-01; BR-07.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14 ("Anticorruption
 * Layer": the other system's vocabulary is translated at the boundary, not adopted).
 */
public enum ExchangeStatus {

    /** Listed and trading. */
    TRADING,

    /** Listed with any other status; NSF-01 flags the pair for an administrator. */
    NOT_TRADING,

    /** Was listed on this market and no longer appears in the exchange information at all. */
    DELISTED
}
