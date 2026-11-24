package com.cryptopilot.drawing.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.drawing.model.ChartPoint;
import com.cryptopilot.drawing.model.enums.DrawingType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The checks a drawing must pass beyond its field constraints: as many anchors as its type has, each at a time a chart
 * can place, a position whose stop and target lie on the right sides of its entry, and a text drawing that has text. A
 * failure names the field, MSG01.
 *
 * <p>Rule: D-149 (2); T-119.
 */
final class DrawingRules {

    /**
     * The span of anchor times kept: from the Unix epoch, where chart time starts, to the last second written with a
     * four-digit year. A later time is written {@code +10000-...}, which a browser does not read back, and one such
     * anchor would keep the whole layout from loading. An anchor may lie after the last candle, so the bound is far.
     */
    static final Instant EARLIEST = Instant.EPOCH;

    static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private DrawingRules() {}

    /** Throws {@link FieldValidationException} when the anchors or the text do not fit the type. */
    static void check(DrawingType type, List<ChartPoint> points, String text) {
        if (points.size() != type.anchors()) {
            throw invalid("points", type + " has " + type.anchors() + " anchors, the request sent " + points.size());
        }
        for (int i = 0; i < points.size(); i++) {
            Instant time = points.get(i).time();
            if (time.isBefore(EARLIEST) || time.isAfter(LATEST)) {
                throw invalid(
                        "points[" + i + "].time",
                        "anchor " + i + " at " + time + " is outside " + EARLIEST + " to " + LATEST);
            }
        }
        switch (type) {
            case LONG_POSITION -> requireOrdered(points.get(1), points.get(0), points.get(2), "a long position");
            case SHORT_POSITION -> requireOrdered(points.get(2), points.get(0), points.get(1), "a short position");
            case TEXT -> {
                if (text == null || text.isBlank()) {
                    throw invalid("text", "a text drawing needs text");
                }
            }
            default -> {
                // The other tools take any anchors.
            }
        }
    }

    /**
     * Requires {@code low < entry < high}. Anchors are {@code [entry, stop, target]}: a long position's stop is the low
     * and its target the high, a short position the other way round.
     */
    private static void requireOrdered(ChartPoint low, ChartPoint entry, ChartPoint high, String what) {
        if (low.price().compareTo(entry.price()) >= 0 || entry.price().compareTo(high.price()) >= 0) {
            throw invalid(
                    "points",
                    what + " needs " + low.price() + " < " + entry.price() + " < " + high.price()
                            + " (stop and target on the right sides of the entry)");
        }
    }

    private static FieldValidationException invalid(String field, String detail) {
        return new FieldValidationException(detail, Map.of(field, "MSG01"));
    }
}
