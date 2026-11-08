package com.cryptopilot.market.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.SetupScoreTunings;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The calibration's statistics and scoring rules, without a database.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 */
class CalibrationReportTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final StylePreset DAY_TRADING = new StylePreset(
            SetupStyle.DAY_TRADING,
            "v2",
            "1h",
            new ComponentWeights(35, 30, 15, 20, null),
            new ComponentWeights(30, 25, 15, 15, 15));

    @Test
    void BR13_percentileAndShare_readTheSortedScores() {
        int[] sorted = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

        assertThat(CalibrationReport.percentile(sorted, 10)).isEqualTo(10);
        assertThat(CalibrationReport.percentile(sorted, 50)).isEqualTo(50);
        assertThat(CalibrationReport.percentile(sorted, 90)).isEqualTo(90);
        assertThat(CalibrationReport.share(sorted, 65)).isEqualTo(0.4);
    }

    @Test
    void BR13_buckets_splitAt55_65_75() {
        assertThat(CalibrationReport.bucket(54)).isZero();
        assertThat(CalibrationReport.bucket(55)).isEqualTo(1);
        assertThat(CalibrationReport.bucket(65)).isEqualTo(2);
        assertThat(CalibrationReport.bucket(75)).isEqualTo(3);
    }

    @Test
    void BR13_monotonic_comparesTheMeansOfNonEmptyBuckets() {
        List<CalibrationReport.Sample> rising = List.of(sample(40, 40, -0.01), sample(70, 70, 0.02));
        List<CalibrationReport.Sample> falling = List.of(sample(40, 40, 0.02), sample(70, 70, -0.01));

        assertThat(CalibrationReport.monotonic(rising, CalibrationReport.Sample::v2))
                .isTrue();
        assertThat(CalibrationReport.monotonic(falling, CalibrationReport.Sample::v2))
                .isFalse();
    }

    @Test
    void BR13_write_givesTheCsvAndASummaryWithTheTargets(@TempDir Path dir) throws IOException {
        List<CalibrationReport.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(sample(i * 11, i * 11, i / 1000.0));
        }

        CalibrationReport.write(samples, dir);

        assertThat(Files.readAllLines(dir.resolve("report.csv"))).hasSize(11);
        String summary = Files.readString(dir.resolve("summary.txt"));
        assertThat(summary)
                .contains("DAY_TRADING SPOT (1h, n=10)")
                .contains("v2 p10/p50/p90 0/44/88")
                .contains("share>=65 in 10-30%: NO")
                .contains("Threshold change proposed: DAY_TRADING SPOT");
    }

    @Test
    void BR13_forwardReturn_isInTheSideDirection_andNeedsConsecutiveCandles() {
        Duration hour = Duration.ofHours(1);
        List<StoredCandle> candles = List.of(
                candle(T0, "100"),
                candle(T0.plus(hour), "101"),
                candle(T0.plus(hour.multipliedBy(2)), "102"),
                candle(T0.plus(hour.multipliedBy(3)), "110"));

        assertThat(CalibrationRunner.forwardReturn(candles, 0, hour, ScoredSide.LONG))
                .isCloseTo(0.10, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(CalibrationRunner.forwardReturn(candles, 0, hour, ScoredSide.SHORT))
                .isCloseTo(-0.10, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(CalibrationRunner.forwardReturn(candles, 1, hour, ScoredSide.LONG))
                .isNull();
        assertThat(CalibrationRunner.forwardReturn(candles, 0, Duration.ofMinutes(15), ScoredSide.LONG))
                .isNull();
    }

    @Test
    void BR13_futuresWithoutDerivatives_weighsTrendMomentumAndLevelOnly() {
        ComponentScores v2 = new ComponentScores(
                MarketType.FUTURES, "1h", "v2", dec("100"), dec("100"), dec("0"), dec("40"), null, ScoredSide.LONG);
        ComponentScores lowRoom = new ComponentScores(
                MarketType.FUTURES, "1h", "v2", dec("100"), dec("100"), dec("0"), dec("5"), null, ScoredSide.LONG);

        // (30·100 + 25·100 + 15·40) / 70 = 87.14
        assertThat(CalibrationRunner.score(DAY_TRADING, v2, false, SetupScoreTunings.DEFAULT))
                .isEqualTo(new CalibrationRunner.Scored(87, false));
        assertThat(CalibrationRunner.score(DAY_TRADING, lowRoom, false, SetupScoreTunings.DEFAULT))
                .isEqualTo(new CalibrationRunner.Scored(54, true));
    }

    private static CalibrationReport.Sample sample(int v1, int v2, double forward) {
        return new CalibrationReport.Sample(
                "BTCUSDT",
                MarketType.SPOT,
                "1h",
                SetupStyle.DAY_TRADING,
                T0,
                ScoredSide.LONG,
                v1,
                v2,
                false,
                false,
                forward);
    }

    private static StoredCandle candle(Instant open, String close) {
        BigDecimal c = new BigDecimal(close);
        return new StoredCandle(open, open.plusSeconds(3599), c, c, c, c, BigDecimal.ONE, c, 1);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
