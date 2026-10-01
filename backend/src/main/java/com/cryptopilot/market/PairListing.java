package com.cryptopilot.market;

import java.util.Objects;
import java.util.UUID;

/**
 * A pair as other modules show it: its symbol and the markets an administrator has it enabled on now (BR-07). A pair
 * that is INACTIVE, or enabled on neither market, is not listed.
 *
 * @param pairId the pair
 * @param symbol the exchange symbol, e.g. {@code BTCUSDT}
 * @param spotListed whether the pair is ACTIVE and enabled on Spot
 * @param futuresListed whether the pair is ACTIVE and enabled on Futures
 */
public record PairListing(UUID pairId, String symbol, boolean spotListed, boolean futuresListed) {

    public PairListing {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(symbol, "symbol");
    }

    /** Whether the pair is shown at all: enabled on at least one market (BR-07). */
    public boolean listed() {
        return spotListed || futuresListed;
    }
}
