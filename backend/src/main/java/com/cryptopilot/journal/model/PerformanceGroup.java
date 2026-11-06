package com.cryptopilot.journal.model;

import java.math.BigDecimal;

/**
 * The statistics of one group of a breakdown.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-42.
 *
 * @param key the pair id, strategy id or market of the group; {@code null} for the trades without a strategy
 * @param counts the counts of the group
 * @param profitFactor gross profit / gross loss, {@code null} without losses
 * @param averageRealizedR the mean realized R, {@code null} when no trade has a risk
 */
public record PerformanceGroup(
        String key, PerformanceCounts counts, BigDecimal profitFactor, BigDecimal averageRealizedR) {}
