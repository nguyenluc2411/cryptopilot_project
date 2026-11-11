package com.cryptopilot.ai.calculator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The day the AI quota counts in: it resets at 00:00 UTC+7 (BR-50).
 *
 * <p>Rule: BR-50.
 */
public final class QuotaDay {

    /** Vietnam time, without daylight saving. */
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(7);

    private QuotaDay() {}

    /** The start of the quota day {@code at} falls in. */
    public static Instant start(Instant at) {
        return LocalDate.ofInstant(at, ZONE).atStartOfDay(ZONE).toInstant();
    }

    /** When the quota of the day {@code at} falls in resets. */
    public static Instant resetsAt(Instant at) {
        return LocalDate.ofInstant(at, ZONE).plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
