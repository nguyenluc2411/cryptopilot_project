package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An administrator has taken a pair's market out of service: its switch went off, or the whole pair went INACTIVE.
 * Published inside the transaction that changed the pair and delivered synchronously, so the ACTIVE plans of the
 * market are cancelled in the same transaction (BR-59) — either both happen or neither.
 *
 * <p>Rule: BR-59; UC-45.
 *
 * @param pairId the pair
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}
 * @param market the market taken out of service
 * @param disabledAt when, from the injected clock
 */
public record PairMarketDisabled(UUID pairId, String symbol, MarketType market, Instant disabledAt) {

    public PairMarketDisabled {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(disabledAt, "disabledAt");
    }
}
