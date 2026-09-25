package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.IndicatorSnapshot;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Compares two indicator snapshots field by field: a {@code null} (not enough data) must be {@code null} on both sides,
 * and a value must lie within {@code max(relative·|expected|, absolute)} of the expected one.
 */
public final class IndicatorAssertions {

    /** Every indicator of a snapshot, by name. */
    public static final Map<String, Function<IndicatorSnapshot, Double>> FIELDS = fields();

    private IndicatorAssertions() {}

    /** Asserts that {@code actual} matches {@code expected} within the tolerance, and names the first field that does not. */
    public static void assertMatches(
            IndicatorSnapshot actual, IndicatorSnapshot expected, double relative, double absolute) {
        assertThat(actual.openTime()).isEqualTo(expected.openTime());
        FIELDS.forEach((name, field) -> {
            Double a = field.apply(actual);
            Double e = field.apply(expected);
            String where = name + " at " + expected.openTime();
            if (e == null) {
                assertThat(a).as(where).isNull();
            } else {
                assertThat(a).as(where).isNotNull();
                assertThat(Math.abs(a - e))
                        .as(where + ": " + a + " vs " + e)
                        .isLessThanOrEqualTo(Math.max(relative * Math.abs(e), absolute));
            }
        });
    }

    /** The largest relative difference over the fields both snapshots have, for reporting. */
    public static double largestRelativeDifference(IndicatorSnapshot actual, IndicatorSnapshot expected) {
        double largest = 0;
        for (Function<IndicatorSnapshot, Double> field : FIELDS.values()) {
            Double a = field.apply(actual);
            Double e = field.apply(expected);
            if (a != null && e != null && e != 0) {
                largest = Math.max(largest, Math.abs(a - e) / Math.abs(e));
            }
        }
        return largest;
    }

    private static Map<String, Function<IndicatorSnapshot, Double>> fields() {
        Map<String, Function<IndicatorSnapshot, Double>> fields = new LinkedHashMap<>();
        fields.put("sma20", IndicatorSnapshot::sma20);
        fields.put("ema20", IndicatorSnapshot::ema20);
        fields.put("ema50", IndicatorSnapshot::ema50);
        fields.put("ema200", IndicatorSnapshot::ema200);
        fields.put("rsi14", IndicatorSnapshot::rsi14);
        fields.put("macdLine", IndicatorSnapshot::macdLine);
        fields.put("macdSignal", IndicatorSnapshot::macdSignal);
        fields.put("macdHistogram", IndicatorSnapshot::macdHistogram);
        fields.put("bbUpper", IndicatorSnapshot::bbUpper);
        fields.put("bbMiddle", IndicatorSnapshot::bbMiddle);
        fields.put("bbLower", IndicatorSnapshot::bbLower);
        fields.put("volumeSma20", IndicatorSnapshot::volumeSma20);
        return Collections.unmodifiableMap(fields);
    }
}
