package com.cryptopilot.common.util;

import com.cryptopilot.common.exception.FieldValidationException;
import java.time.Instant;
import java.util.Map;

/**
 * The bounds a list query puts in place of a time filter the client left out, so a repository query never binds a
 * null instant (PostgreSQL cannot type one) and needs no branch per filter. No row of this application lies outside
 * them.
 *
 * <p>Rule: CR-04.
 */
public final class TimeBounds {

    /** Before every instant the application stores. */
    public static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");

    /** After every instant the application stores. */
    public static final Instant LATEST = Instant.parse("9999-01-01T00:00:00Z");

    private TimeBounds() {}

    /**
     * Refuses a period that does not start before it ends; either bound may be absent.
     *
     * @throws FieldValidationException on {@code to} with MSG15
     */
    public static void requireOrdered(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new FieldValidationException(
                    "from must be before to, was " + from + " and " + to, Map.of("to", "MSG15"));
        }
    }

    /** {@code from}, or {@link #EARLIEST} when absent. */
    public static Instant fromOrEarliest(Instant from) {
        return from == null ? EARLIEST : from;
    }

    /** {@code to}, or {@link #LATEST} when absent. */
    public static Instant toOrLatest(Instant to) {
        return to == null ? LATEST : to;
    }
}
