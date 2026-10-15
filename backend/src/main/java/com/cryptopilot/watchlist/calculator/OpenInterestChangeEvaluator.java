package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.market.OpenInterestSample;
import com.cryptopilot.watchlist.model.IndicatorReading;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;

/**
 * OPEN_INTEREST_CHANGE: the change of open interest, in contracts, over a rolling 60 minutes, in percent (Q-45).
 * For a reading at T the base is the stored reading closest to T − 60 min at or before it, and it must lie within
 * {@link #TOLERANCE} of that instant; a base of zero gives no value. change% = (OI_T − OI_base) / OI_base × 100.
 *
 * <p>Stateless: the previous value of a cross is the change at the stored reading before T, computed the same way
 * from the stored readings, so a restart or a second instance reads the same values. A previous reading further
 * than {@link #TOLERANCE} before T is a gap, and a cross then does not fire.
 *
 * <p>Rule: NSF-06, BR-20; SRS 3.4.2, 3.4.4; A-40; Q-45.
 *
 * <p>Reference: Hull, J. C. (2021). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson (open
 * interest). Murphy, J. J. (1999). <i>Technical Analysis of the Financial Markets</i>. New York Institute of
 * Finance, ch. 7 (volume and open interest).
 */
public final class OpenInterestChangeEvaluator {

    /** The window of the change (SRS 3.4.2: over 1 hour). */
    public static final Duration WINDOW = Duration.ofHours(1);

    /** Two NSF-04 periods of 5 minutes: how far a base or previous reading may be from where it is looked for. */
    public static final Duration TOLERANCE = Duration.ofMinutes(10);

    /** Decimal places of a change in percent: those of the stored threshold, so the two compare exactly. */
    public static final int SCALE = 12;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OpenInterestChangeEvaluator() {}

    /**
     * The change at the reading taken at {@code sampleTime} and at the reading before it.
     *
     * @param atOrBefore the stored reading of the pair at or before an instant
     * @return empty when the reading at {@code sampleTime} or its base is missing
     */
    public static Optional<IndicatorReading> read(
            Instant sampleTime, Function<Instant, Optional<OpenInterestSample>> atOrBefore) {
        Optional<BigDecimal> current = atOrBefore
                .apply(sampleTime)
                .filter(sample -> sample.time().equals(sampleTime))
                .flatMap(sample -> changeAt(sample, atOrBefore));
        if (current.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal previous = atOrBefore
                .apply(sampleTime.minus(1, ChronoUnit.MICROS))
                .filter(sample -> !sample.time().isBefore(sampleTime.minus(TOLERANCE)))
                .flatMap(sample -> changeAt(sample, atOrBefore))
                .orElse(null);
        return Optional.of(new IndicatorReading(previous, current.get()));
    }

    /** The change in percent at this reading; empty without a base within the tolerance, or with a zero base. */
    static Optional<BigDecimal> changeAt(
            OpenInterestSample sample, Function<Instant, Optional<OpenInterestSample>> atOrBefore) {
        Instant target = sample.time().minus(WINDOW);
        return atOrBefore
                .apply(target)
                .filter(base -> !base.time().isBefore(target.minus(TOLERANCE)))
                .filter(base -> base.openInterest().signum() != 0)
                .map(base -> sample.openInterest()
                        .subtract(base.openInterest())
                        .multiply(HUNDRED)
                        .divide(base.openInterest(), SCALE, RoundingMode.HALF_UP));
    }
}
