package com.cryptopilot.market.calculator;

/**
 * The last {@code n} values of a series in a ring buffer, with their running sum updated in O(1): {@code S += x_t −
 * x_{t−n}} (TECHNICAL_DESIGN 7.2). The building block of the simple moving averages and of the Bollinger window.
 *
 * <p>Rule: BR-12; TECHNICAL_DESIGN 7.2.
 */
final class RollingWindow {

    private final double[] values;
    private int next;
    private int count;
    private double sum;

    RollingWindow(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("a window holds at least one value, not " + size);
        }
        this.values = new double[size];
    }

    /** Adds a value, dropping the oldest once the window is full. */
    void add(double value) {
        if (count == values.length) {
            sum -= values[next];
        } else {
            count++;
        }
        values[next] = value;
        sum += value;
        next = (next + 1) % values.length;
    }

    /** Whether the window holds {@code n} values. */
    boolean isFull() {
        return count == values.length;
    }

    /** The running sum of the values held. */
    double sum() {
        return sum;
    }

    /** The mean of the values held; meaningful once full. */
    double mean() {
        return sum / count;
    }

    /**
     * The population standard deviation of the values held, recomputed over the window: exact rather than updated, and
     * shifted by the first value so that a constant window is exactly zero.
     *
     * <p>Reference: Welford, B. P. (1962). Note on a method for calculating corrected sums of squares and products.
     * <i>Technometrics</i>, 4(3), 419–420 (shifting by a sample value before squaring avoids cancellation).
     */
    double populationStandardDeviation() {
        double shift = values[(next - count + values.length) % values.length];
        double shiftedSum = 0;
        for (int i = 0; i < count; i++) {
            shiftedSum += values[i] - shift;
        }
        double shiftedMean = shiftedSum / count;
        double squares = 0;
        for (int i = 0; i < count; i++) {
            double deviation = values[i] - shift - shiftedMean;
            squares += deviation * deviation;
        }
        return Math.sqrt(squares / count);
    }

    /** The number of values held. */
    int size() {
        return count;
    }
}
