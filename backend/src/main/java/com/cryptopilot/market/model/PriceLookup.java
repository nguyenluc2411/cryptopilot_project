package com.cryptopilot.market.model;

import java.util.Optional;

/**
 * The answer of the latest-price cache: what it found and in which state, so that a caller never mistakes an old price
 * for a current one.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6; ADR-005 (Redis is a cache: a miss or an outage is a state, not an error).
 *
 * @param status what the lookup came to
 * @param price the price, present when {@code FOUND} or {@code EXPIRED}, always with its source instant
 */
public record PriceLookup(Status status, Optional<CachedPrice> price) {

    /** The states of a lookup. */
    public enum Status {

        /** A price younger than the cache's time to live. */
        FOUND,

        /** A price older than the time to live: returned with its instant, never as a current price. */
        EXPIRED,

        /** No price is cached for the pair. */
        MISSING,

        /** The cache could not be read. */
        UNAVAILABLE
    }

    /** A lookup that found nothing. */
    public static PriceLookup missing() {
        return new PriceLookup(Status.MISSING, Optional.empty());
    }

    /** A lookup the cache could not answer. */
    public static PriceLookup unavailable() {
        return new PriceLookup(Status.UNAVAILABLE, Optional.empty());
    }
}
