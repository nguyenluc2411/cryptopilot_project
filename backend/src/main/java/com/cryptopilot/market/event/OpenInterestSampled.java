package com.cryptopilot.market.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * NSF-04 has stored a live open interest reading of a Futures pair. Published inside the transaction that writes it,
 * so a transactional listener hears it only once the reading is committed; readings older than the live window, read
 * to catch up after downtime, are stored without it. Listeners read the stored readings through
 * {@code MarketApi#openInterestAtOrBefore} and must be idempotent.
 *
 * <p>Exposed in the named interface {@code events}: the OPEN_INTEREST_CHANGE alerts of the {@code watchlist} module
 * are evaluated on it (NSF-06).
 *
 * <p>Rule: NSF-04, NSF-06; BR-10; SRS 3.4.4; Q-45.
 *
 * <p>Reference: Hull, J. C. (2021). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson (open
 * interest). Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11 (idempotent
 * processing of events).
 *
 * @param pairId the pair
 * @param symbol the pair's symbol
 * @param sampleTime the instant of the reading
 */
@NamedInterface("events")
public record OpenInterestSampled(UUID pairId, String symbol, Instant sampleTime) {

    public OpenInterestSampled {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(sampleTime, "sampleTime");
    }
}
