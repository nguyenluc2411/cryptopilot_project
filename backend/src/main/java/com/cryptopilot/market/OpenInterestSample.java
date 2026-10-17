package com.cryptopilot.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One stored open interest reading of NSF-04: the open contracts of a Futures pair at a 5-minute instant.
 *
 * <p>Rule: NSF-04, NSF-06; BR-10; Q-45.
 *
 * @param time the instant of the reading
 * @param openInterest the open contracts, in base-asset units
 */
public record OpenInterestSample(Instant time, BigDecimal openInterest) {

    public OpenInterestSample {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(openInterest, "openInterest");
    }
}
