package com.cryptopilot.market;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An administrator replaced a pair's leverage brackets. Published inside the transaction that replaced them; who
 * keeps figures computed from the brackets reads them again after the commit: the paper Futures engine its
 * liquidation levels and margin calls (TR-46, plan 9.7).
 *
 * <p>Rule: BR-26, BR-27; UC-46.
 *
 * @param pairId the pair
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}
 * @param replacedAt when, from the injected clock
 */
public record LeverageBracketsReplaced(UUID pairId, String symbol, Instant replacedAt) {

    public LeverageBracketsReplaced {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(replacedAt, "replacedAt");
    }
}
