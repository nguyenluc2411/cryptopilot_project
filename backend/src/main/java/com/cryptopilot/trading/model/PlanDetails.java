package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.EntryType;
import java.time.Instant;
import java.util.Objects;

/**
 * The plan values the risk calculation does not read: how the entry is placed, when an unfilled plan expires and the
 * Trader's note.
 *
 * <p>Rule: SRS 3.5.1; BR-32 (expiry).
 *
 * @param entryType LIMIT or MARKET
 * @param expiresAt when an ACTIVE plan that has not been filled expires; {@code null} for none
 * @param note the Trader's note; {@code null} for none
 */
public record PlanDetails(EntryType entryType, Instant expiresAt, String note) {

    public PlanDetails {
        Objects.requireNonNull(entryType, "entryType");
    }
}
