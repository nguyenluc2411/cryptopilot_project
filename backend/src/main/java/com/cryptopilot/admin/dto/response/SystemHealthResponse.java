package com.cryptopilot.admin.dto.response;

import com.cryptopilot.admin.model.enums.ServiceStatus;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Instant;
import java.util.List;

/**
 * The health panel of SCR-44: each external service and each market's data feed.
 *
 * <p>Rule: UC-52 ("Admin views the status of the external APIs and of data ingestion").
 *
 * @param checkedAt when the figures were read
 * @param recentWindowMinutes how far back a failure makes a service DOWN or DEGRADED
 * @param staleAfterMinutes how old the latest candle may be before a feed is stale: the shortest stored timeframe and
 *     a grace
 * @param systemLogDropped failures not recorded since the start because the system log's queue was full
 */
public record SystemHealthResponse(
        Instant checkedAt,
        int recentWindowMinutes,
        int staleAfterMinutes,
        List<Service> services,
        List<Feed> feeds,
        long systemLogDropped) {

    /**
     * One external service.
     *
     * @param lastSuccessAt when it last answered, as this instance saw it since it started, or {@code null}
     * @param lastFailureAt when it last failed, as the system log keeps it, or {@code null}
     * @param failures24h failures seen in the last 24 hours
     * @param lastError the message of the latest failure, or {@code null}
     * @param bannedUntil for Binance, when its IP ban of this server ends; {@code null} without one
     */
    public record Service(
            SystemEventSource source,
            ServiceStatus status,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            long failures24h,
            String lastError,
            Instant bannedUntil) {}

    /**
     * The data feed of one market.
     *
     * @param market {@code SPOT} or {@code FUTURES}
     * @param latestCandleAt the close of the latest stored candle, or {@code null} when none is stored
     * @param lagSeconds how long ago that was, or {@code null}
     * @param stale whether that is longer ago than {@code staleAfterMinutes}, or nothing is stored
     */
    public record Feed(String market, Instant latestCandleAt, Long lagSeconds, boolean stale) {}
}
