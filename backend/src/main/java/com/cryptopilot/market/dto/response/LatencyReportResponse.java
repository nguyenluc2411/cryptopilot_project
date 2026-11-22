package com.cryptopilot.market.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The realtime latency since the start of this instance (TR-36), and who listens to what (TR-34): for each measured
 * series its count and its 50th, 95th and 99th percentiles and maximum, in milliseconds.
 *
 * @param checkedAt when the figures were read
 * @param series each timer: the market pushes, the paper fills, and the clients' samples
 * @param subscribedTopics how many market topics have at least one subscription
 * @param topTopics the twenty topics with the most subscriptions, most first
 */
public record LatencyReportResponse(
        Instant checkedAt, List<Series> series, int subscribedTopics, Map<String, Integer> topTopics) {

    /**
     * One measured series.
     *
     * @param name the timer, e.g. {@code cryptopilot.market.push.latency}
     * @param tags its tags, e.g. {@code kind=ticker}
     * @param count how many were measured
     * @param p50Ms the median
     * @param p95Ms the 95th percentile
     * @param p99Ms the 99th percentile
     * @param maxMs the largest of the recent ones
     */
    public record Series(
            String name,
            Map<String, String> tags,
            long count,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs) {}
}
