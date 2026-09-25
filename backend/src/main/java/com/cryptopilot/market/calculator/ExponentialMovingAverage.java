package com.cryptopilot.market.calculator;

import java.util.OptionalDouble;

/**
 * The exponential moving average of period {@code n}: {@code EMA_t = α·x_t + (1 − α)·EMA_{t−1}} with {@code α = 2/(n +
 * 1)}, seeded with the simple mean of the first {@code n} values, so the first value is the {@code n}-th; empty before.
 * O(1) per value.
 *
 * <p>Rule: BR-12 (EMA20, EMA50, EMA200; the MACD averages); TECHNICAL_DESIGN 7.2.
 * <p>Reference: Murphy, J. J. (1999). <i>Technical Analysis of the Financial Markets</i>. New York Institute of Finance,
 * ch. 9 (the exponentially smoothed moving average).
 */
public final class ExponentialMovingAverage {

    private final int period;
    private final double alpha;
    private double seedSum;
    private int seen;
    private double value;

    public ExponentialMovingAverage(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("an EMA has a period of at least one, not " + period);
        }
        this.period = period;
        this.alpha = 2.0 / (period + 1);
    }

    /** Takes the next value and answers the average, or empty while fewer than {@code n} values have been seen. */
    public OptionalDouble update(double x) {
        seen++;
        if (seen < period) {
            seedSum += x;
            return OptionalDouble.empty();
        }
        if (seen == period) {
            value = (seedSum + x) / period;
        } else {
            value = alpha * x + (1 - alpha) * value;
        }
        return OptionalDouble.of(value);
    }
}
