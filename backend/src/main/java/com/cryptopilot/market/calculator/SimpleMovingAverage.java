package com.cryptopilot.market.calculator;

import java.util.OptionalDouble;

/**
 * The simple moving average of the last {@code n} values, updated in O(1) per value from a rolling sum; empty until
 * {@code n} values have been seen.
 *
 * <p>Rule: BR-12 (SMA20, Volume SMA20); TECHNICAL_DESIGN 7.2.
 * <p>Reference: Murphy, J. J. (1999). <i>Technical Analysis of the Financial Markets</i>. New York Institute of Finance,
 * ch. 9 (moving averages).
 */
public final class SimpleMovingAverage {

    private final RollingWindow window;

    public SimpleMovingAverage(int period) {
        this.window = new RollingWindow(period);
    }

    /** Takes the next value and answers the average, or empty while fewer than {@code n} values have been seen. */
    public OptionalDouble update(double value) {
        window.add(value);
        return window.isFull() ? OptionalDouble.of(window.mean()) : OptionalDouble.empty();
    }
}
