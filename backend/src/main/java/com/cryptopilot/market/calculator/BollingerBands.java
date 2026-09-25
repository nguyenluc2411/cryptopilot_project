package com.cryptopilot.market.calculator;

/**
 * Bollinger Bands: the middle band is the simple moving average of the last {@code n} closes and the bands lie {@code k}
 * population standard deviations of the same closes above and below it. The deviation is recomputed over the window at
 * every close ({@code n} operations, exact rather than updated), so a window of equal closes has bands equal to the
 * middle. Empty until {@code n} closes have been seen.
 *
 * <p>Rule: BR-12 (Bollinger Bands SMA20 ± 2 standard deviations); TECHNICAL_DESIGN 7.2.
 * <p>Reference: Bollinger, J. (2001). <i>Bollinger on Bollinger Bands</i>. McGraw-Hill (the bands use the population
 * standard deviation of the closes over the same period as the middle band).
 */
public final class BollingerBands {

    /** One reading of the bands. */
    public record Reading(double upper, double middle, double lower) {}

    private final RollingWindow window;
    private final double width;

    public BollingerBands(int period, double width) {
        this.window = new RollingWindow(period);
        this.width = width;
    }

    /** Takes the next close and answers the bands, or null while fewer than {@code n} closes have been seen. */
    public Reading update(double close) {
        window.add(close);
        if (!window.isFull()) {
            return null;
        }
        double middle = window.mean();
        double spread = width * window.populationStandardDeviation();
        return new Reading(middle + spread, middle, middle - spread);
    }
}
