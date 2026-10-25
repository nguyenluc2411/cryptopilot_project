package com.cryptopilot.journal.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The missing funding times of a trade as the PostgreSQL array literal its column is written from, or {@code null}
 * when none is missing, so the column stays null for a complete funding (V36).
 *
 * <p>Rule: BR-37; D-97 note of 2026-10-24.
 */
final class MissingFundingTimes {

    private MissingFundingTimes() {}

    /** {@code {"2026-10-25T08:00:00Z",...}} in ISO-8601 UTC, or {@code null} for an empty list. */
    static String literal(List<Instant> times) {
        if (times.isEmpty()) {
            return null;
        }
        return times.stream().map(time -> "\"" + time + "\"").collect(Collectors.joining(",", "{", "}"));
    }
}
