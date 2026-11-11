package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.journal.model.TradePeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Resolves the period of the journal and of the statistics. Days are those of UTC+7, as the clients show them. A period
 * is {@code [from, to)} of any length: without {@code from} it has no start, without {@code to} it ends with today.
 *
 * <p>Rule: SRS 3.6.1, 3.6.3; UC-22, UC-25; CR-08; D-138, D-139.
 */
final class TradePeriods {

    /** The zone of a business day, as PostgreSQL names it. */
    static final String ZONE_NAME = "Asia/Ho_Chi_Minh";

    private static final ZoneId ZONE = ZoneId.of(ZONE_NAME);
    private static final int FIRST_YEAR = 1;
    private static final int LAST_YEAR = 9999;

    private TradePeriods() {}

    /**
     * The period asked for, validated once resolved, so a {@code from} in the future is refused without a {@code to}
     * too (D-139).
     *
     * @param from the first day, or {@code null}
     * @param to the first day no longer counted, or {@code null} for tomorrow
     * @throws FieldValidationException naming {@code from} or {@code to} with MSG15
     */
    static TradePeriod resolve(LocalDate from, LocalDate to, Clock clock) {
        LocalDate end = end(to, clock);
        Instant endInstant = end.atStartOfDay(ZONE).toInstant();
        if (from == null) {
            return new TradePeriod(null, end, null, endInstant);
        }
        requireStorableYear(from, "from");
        if (!from.isBefore(end)) {
            throw new FieldValidationException("the period starts at or after its end", Map.of("from", "MSG15"));
        }
        return new TradePeriod(from, end, from.atStartOfDay(ZONE).toInstant(), endInstant);
    }

    private static LocalDate end(LocalDate to, Clock clock) {
        if (to == null) {
            return LocalDate.ofInstant(clock.instant(), ZONE).plusDays(1);
        }
        requireStorableYear(to, "to");
        return to;
    }

    private static void requireStorableYear(LocalDate day, String field) {
        if (day.getYear() < FIRST_YEAR || day.getYear() > LAST_YEAR) {
            throw new FieldValidationException("the year is out of range", Map.of(field, "MSG15"));
        }
    }
}
