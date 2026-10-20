package com.cryptopilot.community.model.enums;

import java.time.Duration;

/**
 * How far back the trending topics look (D-99).
 *
 * <p>Rule: D-99.
 */
public enum TrendingWindow {

    /** The last 24 hours. */
    DAY(Duration.ofDays(1)),

    /** The last 7 days. */
    WEEK(Duration.ofDays(7));

    private final Duration length;

    TrendingWindow(Duration length) {
        this.length = length;
    }

    public Duration length() {
        return length;
    }
}
