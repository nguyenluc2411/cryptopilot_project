package com.cryptopilot.market.calculator;

import com.cryptopilot.market.model.IndicatorSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The batch reference the incremental engine is checked against: every indicator computed over the whole series at
 * once, the textbook way — each average summed over its window again, each deviation from its own mean — sharing no
 * code with the production calculators. {@code NaN} marks an index with too few values; it becomes {@code null}.
 */
final class BatchIndicators {

    private BatchIndicators() {}

    /** The snapshot after every candle of the series. */
    static List<IndicatorSnapshot> compute(Instant[] openTimes, double[] closes, double[] volumes) {
        int size = closes.length;
        double[] sma20 = sma(closes, 20);
        double[] ema20 = ema(closes, 20, 0);
        double[] ema50 = ema(closes, 50, 0);
        double[] ema200 = ema(closes, 200, 0);
        double[] rsi14 = rsi(closes, 14);
        double[] ema12 = ema(closes, 12, 0);
        double[] ema26 = ema(closes, 26, 0);
        double[] line = new double[size];
        for (int i = 0; i < size; i++) {
            line[i] = ema12[i] - ema26[i];
        }
        double[] signal = ema(line, 9, 25);
        double[] deviation = populationDeviation(closes, 20);
        double[] volumeSma20 = sma(volumes, 20);
        List<IndicatorSnapshot> snapshots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            snapshots.add(new IndicatorSnapshot(
                    openTimes[i],
                    i + 1,
                    boxed(sma20[i]),
                    boxed(ema20[i]),
                    boxed(ema50[i]),
                    boxed(ema200[i]),
                    boxed(rsi14[i]),
                    boxed(line[i]),
                    boxed(signal[i]),
                    boxed(line[i] - signal[i]),
                    boxed(sma20[i] + 2 * deviation[i]),
                    boxed(sma20[i]),
                    boxed(sma20[i] - 2 * deviation[i]),
                    boxed(volumeSma20[i])));
        }
        return snapshots;
    }

    private static double[] sma(double[] x, int n) {
        double[] out = nans(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double sum = 0;
            for (int j = i - n + 1; j <= i; j++) {
                sum += x[j];
            }
            out[i] = sum / n;
        }
        return out;
    }

    /** The EMA of {@code x} from index {@code start}: seeded with the mean of {@code x[start..start+n-1]}. */
    private static double[] ema(double[] x, int n, int start) {
        double[] out = nans(x.length);
        int first = start + n - 1;
        if (first >= x.length) {
            return out;
        }
        double sum = 0;
        for (int j = start; j <= first; j++) {
            sum += x[j];
        }
        out[first] = sum / n;
        double alpha = 2.0 / (n + 1);
        for (int i = first + 1; i < x.length; i++) {
            out[i] = alpha * x[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    private static double[] rsi(double[] x, int n) {
        double[] out = nans(x.length);
        if (x.length <= n) {
            return out;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= n; i++) {
            gain += Math.max(x[i] - x[i - 1], 0);
            loss += Math.max(x[i - 1] - x[i], 0);
        }
        gain /= n;
        loss /= n;
        out[n] = rsiOf(gain, loss);
        for (int i = n + 1; i < x.length; i++) {
            gain = (gain * (n - 1) + Math.max(x[i] - x[i - 1], 0)) / n;
            loss = (loss * (n - 1) + Math.max(x[i - 1] - x[i], 0)) / n;
            out[i] = rsiOf(gain, loss);
        }
        return out;
    }

    private static double rsiOf(double gain, double loss) {
        return loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
    }

    private static double[] populationDeviation(double[] x, int n) {
        double[] out = nans(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double mean = 0;
            for (int j = i - n + 1; j <= i; j++) {
                mean += x[j];
            }
            mean /= n;
            double squares = 0;
            for (int j = i - n + 1; j <= i; j++) {
                squares += (x[j] - mean) * (x[j] - mean);
            }
            out[i] = Math.sqrt(squares / n);
        }
        return out;
    }

    private static double[] nans(int size) {
        double[] out = new double[size];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    private static Double boxed(double value) {
        return Double.isNaN(value) ? null : value;
    }
}
