package com.cryptopilot.market.calculator;

import java.util.OptionalDouble;

/**
 * Moving average convergence/divergence: {@code line = EMA_fast − EMA_slow} from the close where the slow EMA starts,
 * {@code signal} = the EMA of the line seeded with the mean of its first values, {@code histogram = line − signal}. With
 * (12, 26, 9) the line starts at the 26th close and the signal and histogram at the 34th ({@code 26 + 9 − 1}), as
 * TECHNICAL_DESIGN 7.2 fixes it: each EMA is seeded on its own, so the fast one starts at the 12th close (D-52). O(1)
 * per close.
 *
 * <p>Rule: BR-12 (MACD with EMA12, EMA26 and signal EMA9); TECHNICAL_DESIGN 7.2; D-52.
 * <p>Reference: Appel, G. (2005). <i>Technical Analysis: Power Tools for Active Investors</i>. FT Prentice Hall (MACD).
 */
public final class Macd {

    /** One MACD reading; the signal and histogram are empty until the signal has its seed. */
    public record Reading(double line, OptionalDouble signal, OptionalDouble histogram) {}

    private final ExponentialMovingAverage fast;
    private final ExponentialMovingAverage slow;
    private final ExponentialMovingAverage signal;

    public Macd(int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException("the fast period must be shorter than the slow one");
        }
        this.fast = new ExponentialMovingAverage(fastPeriod);
        this.slow = new ExponentialMovingAverage(slowPeriod);
        this.signal = new ExponentialMovingAverage(signalPeriod);
    }

    /** Takes the next close and answers the reading, or null until the slow EMA has started. */
    public Reading update(double close) {
        OptionalDouble fastValue = fast.update(close);
        OptionalDouble slowValue = slow.update(close);
        if (slowValue.isEmpty()) {
            return null;
        }
        double line = fastValue.getAsDouble() - slowValue.getAsDouble();
        OptionalDouble signalValue = signal.update(line);
        OptionalDouble histogram =
                signalValue.isPresent() ? OptionalDouble.of(line - signalValue.getAsDouble()) : OptionalDouble.empty();
        return new Reading(line, signalValue, histogram);
    }
}
