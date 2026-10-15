package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.watchlist.model.IndicatorReading;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FUNDING_RATE: the predicted funding rate of a Futures pair from the mark price stream, compared with a level the
 * Trader enters in percent. The rate stays a fraction at the exchange's full scale; the level is divided by 100
 * ({@link #level}), exactly, so 0.01 (%) is compared with 0.0001.
 *
 * <p>Keeps the last rate read for each pair as the previous value of a cross, like the previous price of a PRICE
 * alert: a cross needs two readings, and a previous reading older than {@code staleness} is not used.
 *
 * <p>Rule: NSF-06, BR-20; SRS 3.4.2, 3.4.4; Q-30 (percent).
 */
public final class FundingRateEvaluator {

    private final Duration staleness;
    private final Map<UUID, Seen> last = new ConcurrentHashMap<>();

    public FundingRateEvaluator(Duration staleness) {
        this.staleness = staleness;
    }

    /** The Trader's level in percent as a fraction, the unit of the rate. */
    public static BigDecimal level(BigDecimal percent) {
        return percent.movePointLeft(2);
    }

    /** A rate as a fraction in percent, the unit of the Trader's level. */
    public static BigDecimal percent(BigDecimal fraction) {
        return fraction.movePointRight(2);
    }

    /** The reading of this rate against the last one read for the pair, which it then replaces. */
    public IndicatorReading read(UUID pairId, BigDecimal rate, Instant at) {
        Seen before = last.put(pairId, new Seen(rate, at));
        boolean fresh = before != null && !before.at().isBefore(at.minus(staleness));
        return new IndicatorReading(fresh ? before.rate() : null, rate);
    }

    /** No current rate for the pair: the next one starts without a previous value. */
    public void forget(UUID pairId) {
        last.remove(pairId);
    }

    /** Keeps only the pairs that still have FUNDING_RATE alerts. */
    public void retain(Set<UUID> pairIds) {
        last.keySet().retainAll(pairIds);
    }

    private record Seen(BigDecimal rate, Instant at) {}
}
