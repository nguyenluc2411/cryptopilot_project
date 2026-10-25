package com.cryptopilot.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One futures mark price of a pair, from the {@code markPrice@1s} stream: the price liquidation is measured against
 * (D-111 E2).
 *
 * <p>Rule: NSF-03, NSF-07, BR-27; TECHNICAL_DESIGN 7.7.
 *
 * @param pairId the pair
 * @param price the mark price
 * @param eventTime when the exchange produced it
 */
public record MarkPrice(UUID pairId, BigDecimal price, Instant eventTime) {

    public MarkPrice {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
