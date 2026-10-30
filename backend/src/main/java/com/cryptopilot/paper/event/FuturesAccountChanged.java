package com.cryptopilot.paper.event;

import java.util.Objects;
import java.util.UUID;

/**
 * A committed change of a Trader's Futures position or isolated wallet on a pair: a fill, a margin change, a
 * liquidation. Published inside the changing transaction; the matching engine reads it after the commit and watches
 * the Trader's isolated positions on the pair again (plan 9.6 rule 3).
 *
 * <p>Rule: TR-22; D-127.
 */
public record FuturesAccountChanged(UUID userId, UUID pairId) {

    public FuturesAccountChanged {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(pairId, "pairId");
    }
}
