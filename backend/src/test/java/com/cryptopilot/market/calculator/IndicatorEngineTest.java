package com.cryptopilot.market.calculator;

import static com.cryptopilot.market.calculator.IndicatorAssertions.assertMatches;
import static com.cryptopilot.market.calculator.IndicatorAssertions.largestRelativeDifference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.cryptopilot.market.calculator.IndicatorFixture.Row;
import com.cryptopilot.market.model.IndicatorOutcome;
import com.cryptopilot.market.model.IndicatorSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The incremental indicators of one series (TECHNICAL_DESIGN 7.2) against two references: the values an independent
 * library computed for 1200 real candles (the golden fixture), and a batch computation written separately in the tests.
 *
 * <p>Tolerance: a value matches when it lies within {@code max(1e-8·|expected|, 1e-8)} of the reference — far below
 * the 10 decimal places the values are stored with relative to a price, and far above the rounding of a
 * {@code double}, so it catches any formula difference and no rounding noise.
 *
 * <p>Rule: BR-12; SRS 3.3.2; TECHNICAL_DESIGN 7.2; D-52.
 */
class IndicatorEngineTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final double TOLERANCE = 1e-8;

    private final List<Row> fixture = IndicatorFixture.rows();

    /** The fixture is what it says: 1200 consecutive hours. */
    @Test
    void BR12_theGoldenFixture_isTwelveHundredConsecutiveHours() {
        assertThat(fixture).hasSize(1200);
        for (int i = 1; i < fixture.size(); i++) {
            assertThat(fixture.get(i).openTime())
                    .isEqualTo(fixture.get(i - 1).openTime().plus(HOUR));
        }
    }

    /** Every indicator after every candle matches the independent library, including where it has no value yet. */
    @Test
    void BR12_realCandles_matchTheIndependentLibraryWithinTolerance() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        double largest = 0;
        for (Row row : fixture) {
            assertThat(engine.offer(
                            row.openTime(),
                            row.close().doubleValue(),
                            row.volume().doubleValue()))
                    .isEqualTo(IndicatorOutcome.ACCEPTED);
            assertMatches(engine.snapshot(), row.expected(), TOLERANCE, TOLERANCE);
            largest = Math.max(largest, largestRelativeDifference(engine.snapshot(), row.expected()));
        }
        assertThat(engine.snapshot().candles()).isEqualTo(1200);
        assertThat(largest).isLessThan(TOLERANCE);
    }

    /** Incremental equals batch (TECHNICAL_DESIGN 7.2) over the real candles. */
    @Test
    void BR12_realCandles_incrementalMatchesBatch() {
        int size = fixture.size();
        Instant[] times = new Instant[size];
        double[] closes = new double[size];
        double[] volumes = new double[size];
        for (int i = 0; i < size; i++) {
            times[i] = fixture.get(i).openTime();
            closes[i] = fixture.get(i).close().doubleValue();
            volumes[i] = fixture.get(i).volume().doubleValue();
        }
        assertIncrementalMatchesBatch(times, closes, volumes, TOLERANCE, TOLERANCE);
    }

    /**
     * Incremental equals batch over generated series: several seeds and price scales, with runs of unchanged closes so
     * that the average loss and gain reach zero mid-series.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void BR12_generatedSeries_incrementalMatchesBatch(long seed) {
        Random random = new Random(seed);
        double[] scales = {0.00001234, 1.5, 65_000};
        double price = scales[(int) (seed % scales.length)];
        int size = 300 + random.nextInt(300);
        Instant[] times = new Instant[size];
        double[] closes = new double[size];
        double[] volumes = new double[size];
        for (int i = 0; i < size; i++) {
            if (random.nextDouble() >= 0.15) {
                price *= 1 + random.nextGaussian() * 0.01;
            }
            times[i] = START.plus(HOUR.multipliedBy(i));
            closes[i] = price;
            volumes[i] = random.nextDouble() * 1000;
        }
        assertIncrementalMatchesBatch(times, closes, volumes, 1e-9, 1e-10 * scales[(int) (seed % scales.length)]);
    }

    /** SRS 3.3.2: no indicator has a value before it has its candles — never a zero or a partial average. */
    @Test
    void BR12_duringWarmUp_eachIndicatorIsEmptyUntilItHasItsCandles() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        List<IndicatorSnapshot> snapshots = new ArrayList<>();
        for (Row row : fixture.subList(0, 200)) {
            engine.offer(row.openTime(), row.close().doubleValue(), row.volume().doubleValue());
            snapshots.add(engine.snapshot());
        }

        assertFirstValueAt(snapshots, IndicatorSnapshot::rsi14, 14);
        assertFirstValueAt(snapshots, IndicatorSnapshot::sma20, 19);
        assertFirstValueAt(snapshots, IndicatorSnapshot::ema20, 19);
        assertFirstValueAt(snapshots, IndicatorSnapshot::bbUpper, 19);
        assertFirstValueAt(snapshots, IndicatorSnapshot::bbMiddle, 19);
        assertFirstValueAt(snapshots, IndicatorSnapshot::bbLower, 19);
        assertFirstValueAt(snapshots, IndicatorSnapshot::volumeSma20, 19);
        assertFirstValueAt(snapshots, IndicatorSnapshot::macdLine, 25);
        assertFirstValueAt(snapshots, IndicatorSnapshot::macdSignal, 33);
        assertFirstValueAt(snapshots, IndicatorSnapshot::macdHistogram, 33);
        assertFirstValueAt(snapshots, IndicatorSnapshot::ema50, 49);
        assertFirstValueAt(snapshots, IndicatorSnapshot::ema200, 199);
        assertThat(snapshots.get(198).warmedUp()).isFalse();
        assertThat(snapshots.get(199).warmedUp()).isTrue();
        assertThat(snapshots.get(199).candles()).isEqualTo(IndicatorSnapshot.FULL_WARM_UP);
    }

    /** Before its first candle an engine has nothing to report. */
    @Test
    void BR12_beforeTheFirstCandle_thereIsNoSnapshot() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);

        assertThat(engine.snapshot()).isNull();
        assertThat(engine.lastOpenTime()).isNull();
    }

    /** TECHNICAL_DESIGN 10: the last candle again changes nothing, whatever it carries, and the series goes on. */
    @Test
    void BR12_theLastCandleAgain_isIgnored() {
        IndicatorEngine engine = feed(fixture.subList(0, 40));
        IndicatorSnapshot before = engine.snapshot();
        Row last = fixture.get(39);

        assertThat(engine.offer(
                        last.openTime(),
                        last.close().doubleValue(),
                        last.volume().doubleValue()))
                .isEqualTo(IndicatorOutcome.DUPLICATE);
        assertThat(engine.offer(last.openTime(), 1, 1)).isEqualTo(IndicatorOutcome.DUPLICATE);
        assertThat(engine.snapshot()).isSameAs(before);

        for (Row row : fixture.subList(40, 60)) {
            engine.offer(row.openTime(), row.close().doubleValue(), row.volume().doubleValue());
        }
        assertThat(engine.snapshot()).isEqualTo(feed(fixture.subList(0, 60)).snapshot());
    }

    /** A candle older than the last one is refused and leaves the state as it was. */
    @Test
    void BR12_anEarlierCandle_isRefusedAsOutOfOrder() {
        IndicatorEngine engine = feed(fixture.subList(0, 40));
        IndicatorSnapshot before = engine.snapshot();
        Row earlier = fixture.get(20);

        assertThat(engine.offer(earlier.openTime(), earlier.close().doubleValue(), 1))
                .isEqualTo(IndicatorOutcome.OUT_OF_ORDER);
        assertThat(engine.snapshot()).isSameAs(before);
        assertThat(engine.lastOpenTime()).isEqualTo(fixture.get(39).openTime());
    }

    /** A candle that skips one is refused and leaves the state as it was; the missing one is then taken. */
    @Test
    void BR12_aCandleAfterAHole_isRefusedAsAGap() {
        IndicatorEngine engine = feed(fixture.subList(0, 40));
        IndicatorSnapshot before = engine.snapshot();
        Row skipped = fixture.get(41);

        assertThat(engine.offer(skipped.openTime(), skipped.close().doubleValue(), 1))
                .isEqualTo(IndicatorOutcome.GAP);
        assertThat(engine.snapshot()).isSameAs(before);
        Row next = fixture.get(40);
        assertThat(engine.offer(
                        next.openTime(),
                        next.close().doubleValue(),
                        next.volume().doubleValue()))
                .isEqualTo(IndicatorOutcome.ACCEPTED);
    }

    /**
     * A candle's open time must be exactly one interval after the last: an open time off the grid is a gap, not a next
     * candle.
     */
    @Test
    void BR12_aCandleOffTheIntervalGrid_isAGap() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        engine.offer(START, 1, 1);

        assertThat(engine.offer(START.plus(Duration.ofMinutes(30)), 1, 1)).isEqualTo(IndicatorOutcome.GAP);
    }

    /**
     * Restart then continue: an engine built again from the same candles continues exactly as the one that never
     * stopped, bit for bit.
     */
    @Test
    void BR12_rebuiltFromTheSameCandles_continuesIdentically() {
        IndicatorEngine continuous = feed(fixture.subList(0, 600));
        IndicatorEngine restarted = feed(fixture.subList(0, 600));

        for (Row row : fixture.subList(600, 1200)) {
            continuous.offer(
                    row.openTime(), row.close().doubleValue(), row.volume().doubleValue());
            restarted.offer(
                    row.openTime(), row.close().doubleValue(), row.volume().doubleValue());
            assertThat(restarted.snapshot()).isEqualTo(continuous.snapshot());
        }
    }

    /**
     * TECHNICAL_DESIGN 7.2: rebuilt from the latest 1000 candles only, EMA200 lies within 0.05% of its value over the
     * whole history, and every shorter indicator has forgotten where it started.
     */
    @Test
    void BR12_rebuiltFromTheLatestThousandCandles_matchesTheWholeHistory() {
        IndicatorEngine whole = feed(fixture);
        IndicatorEngine window = feed(fixture.subList(200, 1200));

        IndicatorSnapshot a = window.snapshot();
        IndicatorSnapshot b = whole.snapshot();
        assertThat(a.candles()).isEqualTo(1000);
        assertThat(Math.abs(a.ema200() - b.ema200()) / b.ema200()).isLessThanOrEqualTo(5e-4);
        IndicatorSnapshot withoutEma200 = new IndicatorSnapshot(
                b.openTime(),
                b.candles(),
                b.sma20(),
                b.ema20(),
                b.ema50(),
                a.ema200(),
                b.rsi14(),
                b.macdLine(),
                b.macdSignal(),
                b.macdHistogram(),
                b.bbUpper(),
                b.bbMiddle(),
                b.bbLower(),
                b.volumeSma20());
        assertMatches(a, withoutEma200, TOLERANCE, TOLERANCE);
    }

    /**
     * Unchanged closes: the deviation is exactly zero, so the three bands coincide; the averages are the price, the
     * MACD is zero, and the RSI is 100 as TECHNICAL_DESIGN 7.2 fixes it for an average loss of zero (D-52, Q-20).
     */
    @Test
    void BR12_constantCloses_giveZeroDeviationAndTheRsiOfNoLoss() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        for (int i = 0; i < 250; i++) {
            engine.offer(START.plus(HOUR.multipliedBy(i)), 64_123.45, 12.5);
        }

        IndicatorSnapshot s = engine.snapshot();
        assertThat(s.bbUpper()).isEqualTo(s.bbMiddle());
        assertThat(s.bbLower()).isEqualTo(s.bbMiddle());
        assertThat(s.bbMiddle()).isCloseTo(64_123.45, within(1e-9));
        assertThat(s.sma20()).isCloseTo(64_123.45, within(1e-9));
        assertThat(s.ema20()).isCloseTo(64_123.45, within(1e-9));
        assertThat(s.ema50()).isCloseTo(64_123.45, within(1e-9));
        assertThat(s.ema200()).isCloseTo(64_123.45, within(1e-9));
        assertThat(s.macdLine()).isCloseTo(0, within(1e-9));
        assertThat(s.macdSignal()).isCloseTo(0, within(1e-9));
        assertThat(s.macdHistogram()).isCloseTo(0, within(1e-9));
        assertThat(s.rsi14()).isEqualTo(100);
        assertThat(s.volumeSma20()).isCloseTo(12.5, within(1e-12));
    }

    /** After a flat stretch, the first fall takes the RSI below 100 and every band apart. */
    @Test
    void BR12_aFallAfterConstantCloses_movesTheRsiAndTheBands() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        for (int i = 0; i < 30; i++) {
            engine.offer(START.plus(HOUR.multipliedBy(i)), 100, 1);
        }
        engine.offer(START.plus(HOUR.multipliedBy(30)), 99, 1);

        IndicatorSnapshot s = engine.snapshot();
        assertThat(s.rsi14()).isCloseTo(0, within(1e-12));
        assertThat(s.bbUpper()).isGreaterThan(s.bbMiddle());
        assertThat(s.bbLower()).isLessThan(s.bbMiddle());
    }

    @Test
    void BR12_aCandleWithoutAFiniteCloseOrVolume_isRejected() {
        IndicatorEngine engine = new IndicatorEngine(HOUR);

        assertThatThrownBy(() -> engine.offer(START, Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.offer(START, 1, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.offer(null, 1, 1)).isInstanceOf(NullPointerException.class);
        assertThat(engine.snapshot()).isNull();
    }

    @Test
    void BR12_anEngineNeedsAPositiveInterval() {
        assertThatThrownBy(() -> new IndicatorEngine(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorEngine(Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorEngine(null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertIncrementalMatchesBatch(
            Instant[] times, double[] closes, double[] volumes, double relative, double absolute) {
        List<IndicatorSnapshot> batch = BatchIndicators.compute(times, closes, volumes);
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        for (int i = 0; i < closes.length; i++) {
            assertThat(engine.offer(times[i], closes[i], volumes[i])).isEqualTo(IndicatorOutcome.ACCEPTED);
            assertMatches(engine.snapshot(), batch.get(i), relative, absolute);
        }
    }

    private static void assertFirstValueAt(
            List<IndicatorSnapshot> snapshots, Function<IndicatorSnapshot, Double> field, int index) {
        for (int i = 0; i < index; i++) {
            assertThat(field.apply(snapshots.get(i))).as("index " + i).isNull();
        }
        assertThat(field.apply(snapshots.get(index))).as("index " + index).isNotNull();
    }

    private static IndicatorEngine feed(List<Row> rows) {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        for (Row row : rows) {
            engine.offer(row.openTime(), row.close().doubleValue(), row.volume().doubleValue());
        }
        return engine;
    }
}
