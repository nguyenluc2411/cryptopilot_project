package com.cryptopilot.market.calculator;

import java.util.OptionalDouble;

/**
 * The relative strength index with Wilder's smoothing: the average gain and loss seeded with the simple mean of the first
 * {@code n} changes, then {@code AG_t = (AG_{t−1}·(n − 1) + gain_t)/n} and the same for losses; {@code RSI = 100 −
 * 100/(1 + AG/AL)}, and 100 when the average loss is zero, as TECHNICAL_DESIGN 7.2 fixes it — including a series that
 * has not moved at all, where the ratio is 0/0 (Q-20). The first value comes with the {@code (n + 1)}-th close. O(1) per
 * close.
 *
 * <p>Rule: BR-12 (RSI14 with Wilder smoothing); TECHNICAL_DESIGN 7.2; D-52.
 * <p>Reference: Wilder, J. W. (1978). <i>New Concepts in Technical Trading Systems</i>. Trend Research (the Relative
 * Strength Index and its smoothing).
 */
public final class WilderRsi {

    private final int period;
    private double previous;
    private int changes;
    private double averageGain;
    private double averageLoss;
    private boolean started;

    public WilderRsi(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("an RSI has a period of at least one, not " + period);
        }
        this.period = period;
    }

    /** Takes the next close and answers the RSI, or empty until {@code n} changes have been seen. */
    public OptionalDouble update(double close) {
        if (!started) {
            started = true;
            previous = close;
            return OptionalDouble.empty();
        }
        double change = close - previous;
        previous = close;
        double gain = Math.max(change, 0);
        double loss = Math.max(-change, 0);
        changes++;
        if (changes < period) {
            averageGain += gain;
            averageLoss += loss;
            return OptionalDouble.empty();
        }
        if (changes == period) {
            averageGain = (averageGain + gain) / period;
            averageLoss = (averageLoss + loss) / period;
        } else {
            averageGain = (averageGain * (period - 1) + gain) / period;
            averageLoss = (averageLoss * (period - 1) + loss) / period;
        }
        if (averageLoss == 0) {
            return OptionalDouble.of(100);
        }
        return OptionalDouble.of(100 - 100 / (1 + averageGain / averageLoss));
    }
}
