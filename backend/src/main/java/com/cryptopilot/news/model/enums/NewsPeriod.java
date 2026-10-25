package com.cryptopilot.news.model.enums;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * How far back the news feed reaches (SRS 3.8.1): the last 24 hours, 7 days or 30 days, written in a request as
 * {@code 24h}, {@code 7d} or {@code 30d}. Seven days when the Trader picks none: the shortest period that is rarely
 * empty, and the one NSF-11 summarizes in full.
 *
 * <p>Rule: UC-34; SRS 3.8.1.
 */
public enum NewsPeriod {
    LAST_24_HOURS("24h", Duration.ofHours(24)),
    LAST_7_DAYS("7d", Duration.ofDays(7)),
    LAST_30_DAYS("30d", Duration.ofDays(30));

    /** The period of a feed request that names none. */
    public static final NewsPeriod DEFAULT = LAST_7_DAYS;

    private final String code;
    private final Duration length;

    NewsPeriod(String code, Duration length) {
        this.code = code;
        this.length = length;
    }

    /** How the period is written in a request, e.g. {@code 7d}. */
    public String code() {
        return code;
    }

    public Duration length() {
        return length;
    }

    /** The period written as {@code code}, in any case; empty when it is none of them. */
    public static Optional<NewsPeriod> ofCode(String code) {
        return Arrays.stream(values())
                .filter(period -> period.code.equalsIgnoreCase(code.strip()))
                .findFirst();
    }
}
