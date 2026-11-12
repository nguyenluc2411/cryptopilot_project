package com.cryptopilot.market.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SetupScoreTuning;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The five setup score components of one closed candle, 0–100, all read for one scored side: LONG on Spot, the
 * dominant side of the trend on Futures, with the sign of the MACD histogram breaking a tie.
 *
 * <p>Rule: BR-13, BR-21; TECHNICAL_DESIGN 7.4; D-53.
 * <p>Reference: Murphy, J. J. (1999). Technical Analysis of the Financial Markets, ch. 7 (trade with the trend;
 * volume and open interest confirm price).
 */
public final class SetupComponents {

    /** The version of the component formulas below. */
    public static final String FORMULA_VERSION = "v2";

    static final int SCALE = 2;

    static final int LONG = 1;
    static final int SHORT = -1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal TWENTY_FIVE = BigDecimal.valueOf(25);
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private SetupComponents() {}

    /** Computes the component scores; a component whose inputs are missing is {@code null}. */
    public static ComponentScores compute(ComponentInputs in, SetupScoreTuning tuning) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(tuning, "tuning");
        ScoredSide side = scoredSide(in.market(), in.close(), in.indicators());
        if (side == null) {
            return new ComponentScores(
                    in.market(), in.timeframe(), FORMULA_VERSION, null, null, null, null, null, null);
        }
        int d = side.sign();
        BigDecimal sigma = sigma(in.indicators());
        return new ComponentScores(
                in.market(),
                in.timeframe(),
                FORMULA_VERSION,
                scaled(trend(in.close(), in.indicators(), d, band(sigma, tuning.trendBandSigma()))),
                scaled(momentum(in, d)),
                scaled(volume(in, d, tuning)),
                scaled(level(in, d, band(sigma, tuning.levelMinRiskSigma()))),
                in.market() == MarketType.FUTURES ? scaled(derivatives(in.derivatives(), d, tuning)) : null,
                side);
    }

    /**
     * The side a candle is scored for: LONG on Spot; on Futures the dominant side, or the sign of the MACD histogram
     * when the trend is balanced or not readable yet ({@code >= 0} is LONG); {@code null} when neither is known.
     *
     * <p>Rule: BR-13, BR-21.
     * <p>Reference: Murphy, J. J. (1999). Technical Analysis of the Financial Markets, ch. 7 (trade with the trend).
     */
    public static ScoredSide scoredSide(MarketType market, BigDecimal close, IndicatorSnapshot indicators) {
        if (market == MarketType.SPOT) {
            return ScoredSide.LONG;
        }
        DominantSide dominant = dominantSide(market, close, indicators);
        if (dominant == DominantSide.LONG) {
            return ScoredSide.LONG;
        }
        if (dominant == DominantSide.SHORT) {
            return ScoredSide.SHORT;
        }
        Double histogram = indicators.macdHistogram();
        if (histogram == null) {
            return null;
        }
        return histogram >= 0 ? ScoredSide.LONG : ScoredSide.SHORT;
    }

    /**
     * The winning side of the Futures trend conditions, or {@code null} on Spot and when an EMA is missing.
     *
     * @param market the market of the series
     * @param close the candle's close
     * @param indicators the indicators after that candle
     */
    public static DominantSide dominantSide(MarketType market, BigDecimal close, IndicatorSnapshot indicators) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(indicators, "indicators");
        BigDecimal longSide = trend(close, indicators, LONG, BigDecimal.ZERO);
        if (market == MarketType.SPOT || longSide == null) {
            return null;
        }
        int order = longSide.compareTo(trend(close, indicators, SHORT, BigDecimal.ZERO));
        return order > 0 ? DominantSide.LONG : order < 0 ? DominantSide.SHORT : DominantSide.NEUTRAL;
    }

    /**
     * One Bollinger σ, {@code (upper − middle) / 2}, or {@code null} before the bands exist.
     *
     * <p>Rule: BR-12, BR-13.
     * <p>Reference: Bollinger, J. (2001). Bollinger on Bollinger Bands (σ as the volatility unit).
     */
    static BigDecimal sigma(IndicatorSnapshot ind) {
        if (ind.bbUpper() == null || ind.bbMiddle() == null) {
            return null;
        }
        BigDecimal width = BigDecimal.valueOf(ind.bbUpper()).subtract(BigDecimal.valueOf(ind.bbMiddle()));
        return Rounding.divide(width, BigDecimal.TWO);
    }

    private static BigDecimal band(BigDecimal sigma, BigDecimal multiple) {
        return sigma == null ? BigDecimal.ZERO : sigma.multiply(multiple);
    }

    private static BigDecimal trend(BigDecimal close, IndicatorSnapshot ind, int side, BigDecimal band) {
        if (ind.ema20() == null || ind.ema50() == null || ind.ema200() == null) {
            return null;
        }
        BigDecimal ema20 = BigDecimal.valueOf(ind.ema20());
        BigDecimal ema50 = BigDecimal.valueOf(ind.ema50());
        BigDecimal ema200 = BigDecimal.valueOf(ind.ema200());
        int holding = holds(close.subtract(ema20), side, band)
                + holds(ema20.subtract(ema50), side, band)
                + holds(ema50.subtract(ema200), side, band)
                + holds(close.subtract(ema200), side, band);
        return BigDecimal.valueOf(25L * holding);
    }

    private static int holds(BigDecimal difference, int side, BigDecimal band) {
        BigDecimal signed = side == LONG ? difference : difference.negate();
        return signed.signum() > 0 && signed.compareTo(band) > 0 ? 1 : 0;
    }

    /**
     * The RSI part plus the MACD part: 30 when the histogram is on the side, 20 more when it moves toward it.
     *
     * <p>Rule: BR-13.
     * <p>Reference: Appel, G. (2005). Technical Analysis: Power Tools for Active Investors (MACD).
     */
    private static BigDecimal momentum(ComponentInputs in, int side) {
        Double rsi = in.indicators().rsi14();
        Double histogram = in.indicators().macdHistogram();
        Double previous = in.previousMacdHistogram();
        if (rsi == null || histogram == null || previous == null) {
            return null;
        }
        int macd = (side * histogram > 0 ? 30 : 0) + (side * (histogram - previous) > 0 ? 20 : 0);
        return BigDecimal.valueOf(rsiPart(rsi, side)).add(BigDecimal.valueOf(macd));
    }

    /**
     * The RSI part, 0–50: rises from RSI 40 to 50, full from 50 to 65, falls back to 0 at 80; SHORT reads 100 − RSI.
     *
     * <p>Rule: BR-13.
     * <p>Reference: Wilder, J. W. (1978). New Concepts in Technical Trading Systems (RSI).
     */
    static double rsiPart(double rsi, int side) {
        double r = side == LONG ? rsi : 100 - rsi;
        if (r < 40 || r > 80) {
            return 0;
        }
        if (r < 50) {
            return 50 * (r - 40) / 10;
        }
        if (r <= 65) {
            return 50;
        }
        return 50 * (80 - r) / 15;
    }

    /**
     * The volume ratio over its average, full at the saturation ratio, reduced on a candle against the side.
     *
     * <p>Rule: BR-13.
     * <p>Reference: Murphy, J. J. (1999). Technical Analysis of the Financial Markets, ch. 7 (volume confirms price).
     */
    private static BigDecimal volume(ComponentInputs in, int side, SetupScoreTuning tuning) {
        Double average = in.indicators().volumeSma20();
        // An average of zero leaves the ratio undefined, so the component cannot be computed.
        if (average == null || average == 0) {
            return null;
        }
        BigDecimal ratio = Rounding.divide(in.volume(), BigDecimal.valueOf(average));
        BigDecimal score = HUNDRED.multiply(clamp(
                Rounding.divide(ratio.subtract(HALF), tuning.volumeSaturation().subtract(HALF))));
        boolean against = in.open() != null && in.close().compareTo(in.open()) * side < 0;
        return against ? score.multiply(tuning.againstSideFactor()) : score;
    }

    private static BigDecimal level(ComponentInputs in, int side, BigDecimal minRisk) {
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
        BigDecimal room = side == LONG ? above : below;
        BigDecimal risk = (side == LONG ? below : above).max(minRisk);
        return HUNDRED.multiply(clamp(Rounding.divide(Rounding.divide(room, risk), THREE)));
    }

    /**
     * The funding part, even at the baseline and lower for the side that pays above it, plus the open interest part,
     * which rewards a price move on the side that open interest backs.
     *
     * <p>Rule: BR-13.
     * <p>Reference: He, S., Manela, A., Ross, O., von Wachter, V. (2022). Fundamentals of Perpetual Futures,
     * arXiv:2212.06888 (funding is the cost paid by the crowded side).
     */
    private static BigDecimal derivatives(DerivativesInputs in, int side, SetupScoreTuning tuning) {
        if (in == null || in.fundingRate() == null || in.priceChange() == null || in.openInterestChange() == null) {
            return null;
        }
        BigDecimal crowding =
                Rounding.divide(in.fundingRate().subtract(tuning.fundingBaseline()), tuning.fundingSpan());
        BigDecimal funding = FIFTY.multiply(clamp(HALF.subtract(side == LONG ? crowding : crowding.negate())));
        int p = in.priceChange().signum() * side;
        BigDecimal openInterest =
                p < 0 ? BigDecimal.ZERO : p > 0 && in.openInterestChange().signum() > 0 ? FIFTY : TWENTY_FIVE;
        return funding.add(openInterest);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal scaled(BigDecimal score) {
        return score == null ? null : score.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
