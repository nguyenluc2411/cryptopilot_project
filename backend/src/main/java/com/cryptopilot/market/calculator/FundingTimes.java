package com.cryptopilot.market.calculator;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The one rule by which a funding settlement instant is identified, for every path that writes or looks up
 * {@code funding_rate_history}.
 *
 * <p>The exchange stamps a settlement a few milliseconds off its round instant, and not always the same way:
 * the same 08:00 settlement has been seen as {@code …600000} and {@code …600005}. Stored as received, one
 * settlement could become two rows, and a closed position would be charged twice (BR-37). A settlement is
 * therefore identified by its instant rounded to the nearest whole minute, half a minute rounding up.
 *
 * <p>This assumes no funding interval (BR-11): it only relies on two settlements of one symbol never being less
 * than a minute apart, far below the shortest interval the exchange uses (one hour on 2026-09-25).
 *
 * <p>Rule: BR-11, BR-37; NSF-04.
 *
 * <p>Reference: Binance. <i>USDⓈ-M Futures API</i>, "Get Funding Rate History" ({@code fundingTime} in
 * milliseconds, observed a few milliseconds after the round instant).
 */
public final class FundingTimes {

    private static final long MINUTE = Duration.ofMinutes(1).toMillis();
    private static final long HALF_MINUTE = MINUTE / 2;

    private FundingTimes() {}

    /** The instant a settlement is stored and compared by: the source's instant rounded to the nearest minute. */
    public static Instant normalize(Instant fundingTime) {
        Objects.requireNonNull(fundingTime, "fundingTime must not be null");
        long millis = fundingTime.toEpochMilli();
        return Instant.ofEpochMilli(Math.floorDiv(millis + HALF_MINUTE, MINUTE) * MINUTE);
    }

    /**
     * The earliest source instant that can belong to a later settlement than the stored one: every stamp of the
     * stored settlement normalizes to it and so lies before this.
     */
    public static Instant after(Instant storedFundingTime) {
        Objects.requireNonNull(storedFundingTime, "storedFundingTime must not be null");
        return normalize(storedFundingTime).plusMillis(HALF_MINUTE);
    }
}
