package com.cryptopilot.market.tool;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.IntFunction;

/**
 * The v1 component formulas, kept only so the calibration can compare v1 with v2 on the same candles.
 *
 * <p>Rule: BR-13, BR-21; TECHNICAL_DESIGN 7.4 (v1).
 * <p>Reference: Wilder, J. W. (1978). New Concepts in Technical Trading Systems (RSI).
 */
final class V1SetupComponents {

    static final int LONG = 1;
    static final int SHORT = -1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal FUNDING_OFFSET = new BigDecimal("0.0005");
    private static final BigDecimal FUNDING_SPAN = new BigDecimal("0.001");

    private V1SetupComponents() {}

    static ComponentScores compute(ComponentInputs in) {
        boolean futures = in.market() == MarketType.FUTURES;
        return new ComponentScores(
                in.market(),
                in.timeframe(),
                "v1",
                scaled(directional(futures, side -> trend(in.close(), in.indicators(), side))),
                scaled(directional(futures, side -> momentum(in, side))),
                scaled(volume(in)),
                scaled(directional(futures, side -> level(in, side))),
                futures ? scaled(derivatives(in.derivatives())) : null);
    }

    private static BigDecimal directional(boolean futures, IntFunction<BigDecimal> reading) {
        BigDecimal longSide = reading.apply(LONG);
        if (!futures || longSide == null) {
            return longSide;
        }
        return longSide.max(reading.apply(SHORT));
    }

    private static BigDecimal trend(BigDecimal close, IndicatorSnapshot ind, int side) {
        if (ind.ema20() == null || ind.ema50() == null || ind.ema200() == null) {
            return null;
        }
        BigDecimal ema20 = BigDecimal.valueOf(ind.ema20());
        BigDecimal ema50 = BigDecimal.valueOf(ind.ema50());
        BigDecimal ema200 = BigDecimal.valueOf(ind.ema200());
        int holding = holds(close.subtract(ema20), side)
                + holds(ema20.subtract(ema50), side)
                + holds(ema50.subtract(ema200), side)
                + holds(close.subtract(ema200), side);
        return BigDecimal.valueOf(25L * holding);
    }

    private static int holds(BigDecimal difference, int side) {
        return difference.signum() * side > 0 ? 1 : 0;
    }

    private static BigDecimal momentum(ComponentInputs in, int side) {
        Double rsi = in.indicators().rsi14();
        Double histogram = in.indicators().macdHistogram();
        Double previous = in.previousMacdHistogram();
        if (rsi == null || histogram == null || previous == null) {
            return null;
        }
        int macd = (side * histogram > 0 ? 30 : 0) + (side * (histogram - previous) > 0 ? 20 : 0);
        return BigDecimal.valueOf(rsiPart(rsi, side) + macd);
    }

    static int rsiPart(double rsi, int side) {
        if (side == LONG) {
            if (rsi >= 50 && rsi <= 70) {
                return 50;
            }
            return (rsi >= 40 && rsi < 50) || (rsi > 70 && rsi <= 80) ? 25 : 0;
        }
        if (rsi >= 30 && rsi <= 50) {
            return 50;
        }
        return (rsi > 50 && rsi <= 60) || (rsi >= 20 && rsi < 30) ? 25 : 0;
    }

    private static BigDecimal volume(ComponentInputs in) {
        Double average = in.indicators().volumeSma20();
        if (average == null || average == 0) {
            return null;
        }
        BigDecimal ratio = Rounding.divide(in.volume(), BigDecimal.valueOf(average));
        return HUNDRED.multiply(clamp(ratio.subtract(HALF)));
    }

    private static BigDecimal level(ComponentInputs in, int side) {
        BigDecimal support = in.support();
        BigDecimal resistance = in.resistance();
        if (support == null || resistance == null) {
            return FIFTY;
        }
        BigDecimal below = in.close().subtract(support);
        BigDecimal above = resistance.subtract(in.close());
        if (below.signum() <= 0 || above.signum() <= 0) {
            throw new IllegalArgumentException("support must lie below the close and resistance above it");
        }
        BigDecimal q = side == LONG ? Rounding.divide(above, below) : Rounding.divide(below, above);
        return HUNDRED.multiply(clamp(Rounding.divide(q, THREE)));
    }

    private static BigDecimal derivatives(DerivativesInputs in) {
        if (in == null || in.fundingRate() == null || in.priceChange() == null || in.openInterestChange() == null) {
            return null;
        }
        BigDecimal funding = FIFTY.multiply(
                clamp(Rounding.divide(FUNDING_OFFSET.add(in.fundingRate().abs()), FUNDING_SPAN)));
        int openInterest =
                in.priceChange().signum() == 0 ? 0 : in.openInterestChange().signum() > 0 ? 50 : 25;
        return funding.add(BigDecimal.valueOf(openInterest));
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal scaled(BigDecimal score) {
        return score == null ? null : score.setScale(2, RoundingMode.HALF_UP);
    }
}
