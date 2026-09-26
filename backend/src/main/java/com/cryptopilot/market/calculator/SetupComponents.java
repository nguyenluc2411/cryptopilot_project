package com.cryptopilot.market.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.DominantSide;
import com.cryptopilot.market.model.IndicatorSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * The five setup score components of one closed candle, 0–100: trend, momentum and level read LONG-only on Spot and
 * {@code max(LONG, SHORT)} on Futures; volume and derivatives the same on both.
 *
 * <p>Rule: BR-13, BR-21; TECHNICAL_DESIGN 7.4; D-53.
 * <p>Reference: Wilder, J. W., New Concepts in Technical Trading Systems, 1978.
 */
public final class SetupComponents {

    /** The version of the component formulas below. */
    public static final String FORMULA_VERSION = "v1";

    static final int SCALE = 2;

    static final int LONG = 1;
    static final int SHORT = -1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal FUNDING_OFFSET = new BigDecimal("0.0005");
    private static final BigDecimal FUNDING_SPAN = new BigDecimal("0.001");

    private SetupComponents() {}

    /** Computes the component scores; a component whose inputs are missing is {@code null}. */
    public static ComponentScores compute(ComponentInputs in) {
        Objects.requireNonNull(in, "in");
        boolean futures = in.market() == MarketType.FUTURES;
        return new ComponentScores(
                in.market(),
                in.timeframe(),
                FORMULA_VERSION,
                scaled(directional(futures, side -> trend(in.close(), in.indicators(), side))),
                scaled(directional(futures, side -> momentum(in, side))),
                scaled(volume(in)),
                scaled(directional(futures, side -> level(in, side))),
                futures ? scaled(derivatives(in.derivatives())) : null);
    }

    /**
     * The winning side of the Futures trend component, or {@code null} on Spot and when an EMA is missing.
     *
     * @param market the market of the series
     * @param close the candle's close
     * @param indicators the indicators after that candle
     */
    public static DominantSide dominantSide(MarketType market, BigDecimal close, IndicatorSnapshot indicators) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(indicators, "indicators");
        BigDecimal longSide = trend(close, indicators, LONG);
        if (market == MarketType.SPOT || longSide == null) {
            return null;
        }
        int order = longSide.compareTo(trend(close, indicators, SHORT));
        return order > 0 ? DominantSide.LONG : order < 0 ? DominantSide.SHORT : DominantSide.NEUTRAL;
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
        // An average of zero leaves the ratio undefined, so the component cannot be computed.
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
        return score == null ? null : score.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
