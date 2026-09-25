package com.cryptopilot.market.calculator;

import com.cryptopilot.market.model.StoredCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The nearest swing low below and swing high above the last close, over the last 200 closed candles.
 *
 * <p>Rule: BR-14; TECHNICAL_DESIGN 7.3.
 * <p>Reference: Pivot definition follows Williams fractals (Williams, B., New Trading Dimensions, 1998).
 */
public final class SwingSupportResistance {

    /** The two levels; either is {@code null} when no swing qualifies. Prices only, no direction (A-07). */
    public record Levels(BigDecimal support, BigDecimal resistance) {}

    static final int WINDOW = 200;
    static final int NEIGHBOURS = 2;

    private SwingSupportResistance() {}

    /**
     * Finds the levels relative to the close of the last candle.
     *
     * @param candles consecutive closed candles, oldest first
     */
    public static Levels nearest(List<StoredCandle> candles) {
        Objects.requireNonNull(candles, "candles");
        int end = candles.size();
        if (end == 0) {
            return new Levels(null, null);
        }
        int start = Math.max(0, end - WINDOW);
        BigDecimal close = candles.get(end - 1).close();
        BigDecimal support = null;
        BigDecimal resistance = null;
        // The last NEIGHBOURS candles still lack their right-hand neighbours, so they are never candidates.
        for (int i = start + NEIGHBOURS; i < end - NEIGHBOURS; i++) {
            BigDecimal low = candles.get(i).low();
            if (low.compareTo(close) < 0
                    && (support == null || low.compareTo(support) > 0)
                    && isExtreme(candles, i, StoredCandle::low, -1)) {
                support = low;
            }
            BigDecimal high = candles.get(i).high();
            if (high.compareTo(close) > 0
                    && (resistance == null || high.compareTo(resistance) < 0)
                    && isExtreme(candles, i, StoredCandle::high, 1)) {
                resistance = high;
            }
        }
        return new Levels(support, resistance);
    }

    /** Whether candle {@code i} is strictly beyond each of its neighbours in {@code sign}'s direction. */
    private static boolean isExtreme(
            List<StoredCandle> candles, int i, Function<StoredCandle, BigDecimal> price, int sign) {
        BigDecimal value = price.apply(candles.get(i));
        for (int k = 1; k <= NEIGHBOURS; k++) {
            if (Integer.signum(value.compareTo(price.apply(candles.get(i - k)))) != sign
                    || Integer.signum(value.compareTo(price.apply(candles.get(i + k)))) != sign) {
                return false;
            }
        }
        return true;
    }
}
