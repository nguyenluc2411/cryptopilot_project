package com.cryptopilot.market.tool;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Writes the calibration samples as {@code report.csv} (one line per candle and preset) and {@code summary.txt} (the
 * distribution of v1 and v2 per preset and market, checked against the v2 targets).
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 */
final class CalibrationReport {

    static final int[] THRESHOLDS = {55, 65, 75};
    static final double TARGET_MIN = 0.10;
    static final double TARGET_MAX = 0.30;
    static final double TARGET_GAP = 0.10;

    private CalibrationReport() {}

    /**
     * One candle scored under one preset.
     *
     * @param forwardReturn the return over the next 3 candles in the scored side's direction, or {@code null}
     */
    record Sample(
            String symbol,
            MarketType market,
            String timeframe,
            SetupStyle preset,
            Instant openTime,
            ScoredSide side,
            int v1,
            int v2,
            boolean lowRoom,
            boolean hasDerivatives,
            Double forwardReturn) {}

    static void write(List<Sample> samples, Path out) throws IOException {
        Files.createDirectories(out);
        Files.write(out.resolve("report.csv"), csv(samples), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("summary.txt"), summary(samples), StandardCharsets.UTF_8);
    }

    static List<String> csv(List<Sample> samples) {
        List<String> lines = new ArrayList<>();
        lines.add("symbol,market,timeframe,preset,open_time,scored_side,v1,v2,low_room,has_derivatives,forward_3");
        for (Sample s : samples) {
            lines.add(String.join(
                    ",",
                    s.symbol(),
                    s.market().name(),
                    s.timeframe(),
                    s.preset().name(),
                    s.openTime().toString(),
                    s.side().name(),
                    String.valueOf(s.v1()),
                    String.valueOf(s.v2()),
                    String.valueOf(s.lowRoom()),
                    String.valueOf(s.hasDerivatives()),
                    s.forwardReturn() == null ? "" : String.format(Locale.ROOT, "%.6f", s.forwardReturn())));
        }
        return lines;
    }

    static String summary(List<Sample> samples) {
        StringBuilder b = new StringBuilder();
        b.append("Setup score calibration: v1 vs v2, ").append(samples.size()).append(" samples\n");
        b.append("Score by distribution only, not a backtest; forward return is a sanity check.\n\n");
        List<String> proposals = new ArrayList<>();
        boolean allMet = true;
        for (SetupStyle preset : SetupStyle.values()) {
            double[] share65 = new double[2];
            for (MarketType market : MarketType.values()) {
                List<Sample> group = samples.stream()
                        .filter(s -> s.preset() == preset && s.market() == market)
                        .toList();
                b.append("== ").append(preset).append(' ').append(market);
                if (group.isEmpty()) {
                    b.append(": no samples\n\n");
                    continue;
                }
                b.append(" (")
                        .append(group.getFirst().timeframe())
                        .append(", n=")
                        .append(group.size())
                        .append(")\n");
                if (market == MarketType.FUTURES) {
                    long withData =
                            group.stream().filter(Sample::hasDerivatives).count();
                    b.append(String.format(
                            Locale.ROOT, "  Futures candles with derivatives data: %d/%d%n", withData, group.size()));
                }
                for (String version : List.of("v1", "v2")) {
                    ToIntFunction<Sample> score = "v1".equals(version) ? Sample::v1 : Sample::v2;
                    int[] sorted = group.stream().mapToInt(score).sorted().toArray();
                    b.append(String.format(
                            Locale.ROOT,
                            "  %s p10/p50/p90 %d/%d/%d  share>=55 %s  >=65 %s  >=75 %s%n",
                            version,
                            percentile(sorted, 10),
                            percentile(sorted, 50),
                            percentile(sorted, 90),
                            pct(share(sorted, 55)),
                            pct(share(sorted, 65)),
                            pct(share(sorted, 75))));
                    b.append("    forward 3 by bucket:")
                            .append(buckets(group, score))
                            .append('\n');
                    if ("v2".equals(version)) {
                        double s65 = share(sorted, 65);
                        share65[market.ordinal()] = s65;
                        boolean monotonic = monotonic(group, score);
                        boolean inRange = s65 >= TARGET_MIN && s65 <= TARGET_MAX;
                        allMet &= inRange && monotonic;
                        b.append(String.format(
                                Locale.ROOT,
                                "    LOW_ROOM share %s; share>=65 in 10-30%%: %s; buckets non-decreasing: %s%n",
                                pct(group.stream().filter(Sample::lowRoom).count() / (double) group.size()),
                                inRange ? "yes" : "NO",
                                monotonic ? "yes" : "NO"));
                        if (!inRange) {
                            proposals.add(String.format(
                                    Locale.ROOT,
                                    "%s %s: threshold %d gives a 20%% share (65 gives %s)",
                                    preset,
                                    market,
                                    percentile(sorted, 80),
                                    pct(s65)));
                        }
                    }
                }
                b.append('\n');
            }
            double gap = Math.abs(share65[0] - share65[1]);
            allMet &= gap <= TARGET_GAP;
            b.append(String.format(
                    Locale.ROOT,
                    "  %s Spot/Futures gap in v2 share>=65: %s (target <= 10 pp: %s)%n%n",
                    preset,
                    pct(gap),
                    gap <= TARGET_GAP ? "yes" : "NO"));
        }
        b.append("All v2 targets met: ").append(allMet ? "yes" : "no").append('\n');
        b.append("Threshold change proposed: ")
                .append(proposals.isEmpty() ? "none" : String.join("; ", proposals))
                .append('\n');
        return b.toString();
    }

    /** Nearest-rank percentile of sorted scores. */
    static int percentile(int[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    static double share(int[] sorted, int threshold) {
        long above = java.util.Arrays.stream(sorted).filter(s -> s >= threshold).count();
        return above / (double) sorted.length;
    }

    private static String buckets(List<Sample> group, ToIntFunction<Sample> score) {
        StringBuilder b = new StringBuilder();
        String[] names = {"<55", "55-64", "65-74", ">=75"};
        double[] means = means(group, score);
        long[] counts = counts(group, score);
        for (int i = 0; i < names.length; i++) {
            b.append(String.format(
                    Locale.ROOT,
                    "  %s %s (n=%d)",
                    names[i],
                    counts[i] == 0 ? "-" : String.format(Locale.ROOT, "%+.4f%%", means[i] * 100),
                    counts[i]));
        }
        return b.toString();
    }

    /** Whether the mean forward return never falls from one non-empty bucket to the next. */
    static boolean monotonic(List<Sample> group, ToIntFunction<Sample> score) {
        double[] means = means(group, score);
        long[] counts = counts(group, score);
        Double last = null;
        for (int i = 0; i < means.length; i++) {
            if (counts[i] == 0) {
                continue;
            }
            if (last != null && means[i] < last) {
                return false;
            }
            last = means[i];
        }
        return true;
    }

    static int bucket(int score) {
        return score < THRESHOLDS[0] ? 0 : score < THRESHOLDS[1] ? 1 : score < THRESHOLDS[2] ? 2 : 3;
    }

    private static double[] means(List<Sample> group, ToIntFunction<Sample> score) {
        double[] sums = new double[4];
        long[] counts = counts(group, score);
        for (Sample s : group) {
            if (s.forwardReturn() != null) {
                sums[bucket(score.applyAsInt(s))] += s.forwardReturn();
            }
        }
        for (int i = 0; i < 4; i++) {
            sums[i] = counts[i] == 0 ? 0 : sums[i] / counts[i];
        }
        return sums;
    }

    private static long[] counts(List<Sample> group, ToIntFunction<Sample> score) {
        long[] counts = new long[4];
        group.stream()
                .filter(s -> Objects.nonNull(s.forwardReturn()))
                .forEach(s -> counts[bucket(score.applyAsInt(s))]++);
        return counts;
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }
}
