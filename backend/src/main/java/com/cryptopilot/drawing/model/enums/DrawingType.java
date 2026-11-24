package com.cryptopilot.drawing.model.enums;

/**
 * The tools a Trader draws with on the chart, each with the number of anchors (a candle open time and a price) that
 * fixes its shape. A ray and an extended line are a {@link #TREND_LINE} whose style extends it to one or both sides.
 *
 * <p>Rule: D-149 (2); T-119.
 */
public enum DrawingType {
    /** A segment between two anchors. */
    TREND_LINE(2),
    /** A line across the whole chart at the anchor's price. */
    HORIZONTAL_LINE(1),
    /** A line from the anchor to the right edge at its price. */
    HORIZONTAL_RAY(1),
    /** A line across the whole chart at the anchor's time. */
    VERTICAL_LINE(1),
    /** A price zone between two opposite corners. */
    RECTANGLE(2),
    /** Retracement levels between a swing start and a swing end. */
    FIB_RETRACEMENT(2),
    /** A long trade idea: entry, stop below it, target above it. */
    LONG_POSITION(3),
    /** A short trade idea: entry, stop above it, target below it. */
    SHORT_POSITION(3),
    /** A note at one anchor. */
    TEXT(1),
    /** Two parallel lines: a base segment and a point the parallel passes through. */
    PARALLEL_CHANNEL(3),
    /** Extension levels projected from a swing and its pullback. */
    FIB_EXTENSION(3),
    /** The price distance between two anchors. */
    PRICE_RANGE(2),
    /** The time distance between two anchors. */
    DATE_RANGE(2),
    /** An arrow from the first anchor to the second. */
    ARROW(2),
    /** A harmonic pattern through the swings X, A, B, C and D, with the Fibonacci ratio of each leg. */
    XABCD_PATTERN(5),
    /** A Cypher harmonic pattern: X, A, B, C (an extension of XA) and D (a retracement of XC). */
    CYPHER_PATTERN(5),
    /** Two equal legs: A to B, a pullback to C, and C to D. */
    ABCD_PATTERN(4),
    /** Three drives: a start, drive 1, pullback A, drive 2, pullback B and drive 3. */
    THREE_DRIVES_PATTERN(6),
    /** A head and shoulders: start, left shoulder, neck, head, neck, right shoulder, end. */
    HEAD_AND_SHOULDERS(7),
    /** A converging triangle through four alternating swings. */
    TRIANGLE_PATTERN(4),
    /** An Elliott impulse: origin 0 and waves 1 to 5. */
    ELLIOTT_IMPULSE_WAVE(6),
    /** An Elliott correction: origin and waves A, B, C. */
    ELLIOTT_CORRECTION_WAVE(4),
    /** An Elliott triangle: origin and waves A to E. */
    ELLIOTT_TRIANGLE_WAVE(6),
    /** An Elliott double combination: origin and waves W, X, Y. */
    ELLIOTT_DOUBLE_COMBO(4),
    /** An Elliott triple combination: origin and waves W, X, Y, X, Z. */
    ELLIOTT_TRIPLE_COMBO(6);

    /** The most anchors any type has. */
    public static final int MAX_ANCHORS = 7;

    private final int anchors;

    DrawingType(int anchors) {
        this.anchors = anchors;
    }

    /** How many anchors a drawing of this type has, exactly. */
    public int anchors() {
        return anchors;
    }
}
