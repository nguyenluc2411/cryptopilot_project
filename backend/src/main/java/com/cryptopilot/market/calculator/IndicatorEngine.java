package com.cryptopilot.market.calculator;

import com.cryptopilot.market.model.IndicatorOutcome;
import com.cryptopilot.market.model.IndicatorSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The indicators of one candle series, kept up to date one closed candle at a time: each indicator holds its own state
 * and takes the new close in O(1) — the Bollinger deviation in O(20) — so nothing is recomputed over the series
 * (TECHNICAL_DESIGN 7.2).
 *
 * <p>The values are only right over consecutive candles, so a candle is taken only when it opens exactly one interval
 * after the last one. The last candle again is a {@link IndicatorOutcome#DUPLICATE}; an earlier one is
 * {@link IndicatorOutcome#OUT_OF_ORDER}; a later one leaves a {@link IndicatorOutcome#GAP}. All three leave the state as
 * it was: the caller decides whether to rebuild it.
 *
 * <p>Rule: BR-12 (SMA20, EMA20/50/200, RSI14, MACD 12/26/9, Bollinger 20/2, volume SMA20); SRS 3.3.2; TECHNICAL_DESIGN
 * 7.2.
 * <p>Reference: Murphy, J. J. (1999). <i>Technical Analysis of the Financial Markets</i>. New York Institute of Finance
 * (the indicators are computed over the closes of consecutive periods of one timeframe).
 */
public final class IndicatorEngine {

    private final Duration interval;
    private final ExponentialMovingAverage ema20 = new ExponentialMovingAverage(20);
    private final ExponentialMovingAverage ema50 = new ExponentialMovingAverage(50);
    private final ExponentialMovingAverage ema200 = new ExponentialMovingAverage(200);
    private final WilderRsi rsi14 = new WilderRsi(14);
    private final Macd macd = new Macd(12, 26, 9);
    private final BollingerBands bands = new BollingerBands(20, 2);
    private final SimpleMovingAverage volumeSma20 = new SimpleMovingAverage(20);

    private Instant lastOpenTime;
    private IndicatorSnapshot snapshot;

    /** @param interval the length of one candle of the series */
    public IndicatorEngine(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("a candle lasts a positive time, not " + interval);
        }
        this.interval = interval;
    }

    /**
     * Offers the next closed candle.
     *
     * @param openTime the candle's open time
     * @param close its close price
     * @param volume its base-asset volume
     * @return {@link IndicatorOutcome#ACCEPTED} when every indicator took it, or why it was refused
     */
    public IndicatorOutcome offer(Instant openTime, double close, double volume) {
        Objects.requireNonNull(openTime, "openTime");
        if (!Double.isFinite(close) || !Double.isFinite(volume)) {
            throw new IllegalArgumentException("a candle has a finite close and volume");
        }
        if (lastOpenTime != null) {
            int order = openTime.compareTo(lastOpenTime);
            if (order == 0) {
                return IndicatorOutcome.DUPLICATE;
            }
            if (order < 0) {
                return IndicatorOutcome.OUT_OF_ORDER;
            }
            if (!openTime.equals(lastOpenTime.plus(interval))) {
                return IndicatorOutcome.GAP;
            }
        }
        take(openTime, close, volume);
        return IndicatorOutcome.ACCEPTED;
    }

    /** The open time of the last candle taken, or {@code null} before the first. */
    public Instant lastOpenTime() {
        return lastOpenTime;
    }

    /** The indicators after the last candle taken, or {@code null} before the first. */
    public IndicatorSnapshot snapshot() {
        return snapshot;
    }

    private void take(Instant openTime, double close, double volume) {
        OptionalDouble e20 = ema20.update(close);
        OptionalDouble e50 = ema50.update(close);
        OptionalDouble e200 = ema200.update(close);
        OptionalDouble rsi = rsi14.update(close);
        Macd.Reading m = macd.update(close);
        BollingerBands.Reading b = bands.update(close);
        OptionalDouble vol = volumeSma20.update(volume);
        int candles = snapshot == null ? 1 : snapshot.candles() + 1;
        lastOpenTime = openTime;
        snapshot = new IndicatorSnapshot(
                openTime,
                candles,
                // SMA20 is the middle band: the same mean of the same 20 closes, kept once.
                b == null ? null : b.middle(),
                boxed(e20),
                boxed(e50),
                boxed(e200),
                boxed(rsi),
                m == null ? null : m.line(),
                m == null ? null : boxed(m.signal()),
                m == null ? null : boxed(m.histogram()),
                b == null ? null : b.upper(),
                b == null ? null : b.middle(),
                b == null ? null : b.lower(),
                boxed(vol));
    }

    private static Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }
}
