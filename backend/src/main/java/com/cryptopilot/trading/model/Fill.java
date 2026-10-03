package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An entry the matching engine has decided to fill: the price and the time the fill is recorded with.
 *
 * <p>Rule: BR-33, NSF-07; D-78 (the time is the open time of the candle that reached the entry).
 *
 * @param planId the plan
 * @param price the fill price; for a LIMIT entry reached by a candle, its entry price (BR-33)
 * @param executedAt when the plan counts as filled
 */
public record Fill(UUID planId, BigDecimal price, Instant executedAt) {

    public Fill {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(executedAt, "executedAt");
    }
}
