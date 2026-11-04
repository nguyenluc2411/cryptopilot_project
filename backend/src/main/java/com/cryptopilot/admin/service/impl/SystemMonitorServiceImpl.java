package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.config.SystemHealthProperties;
import com.cryptopilot.admin.dto.response.SystemEventResponse;
import com.cryptopilot.admin.dto.response.SystemHealthResponse;
import com.cryptopilot.admin.model.SourceFailures;
import com.cryptopilot.admin.model.SystemEventFilter;
import com.cryptopilot.admin.model.SystemEventRow;
import com.cryptopilot.admin.model.enums.ServiceStatus;
import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.repository.SystemEventStore;
import com.cryptopilot.admin.service.SystemMonitorService;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The system log and the health panel of SCR-44.
 *
 * <p>A service's status weighs its latest failure, from the system log, against its latest answer, held in memory by
 * {@link ExternalCallTracker}: a failure within {@link #RECENT} with no answer after it is DOWN (for the news sites:
 * with no answer from any of them within it), one with an answer
 * after it DEGRADED; an answer and no recent failure is UP, and neither since the start UNKNOWN. A Binance IP ban that
 * has not ended is DOWN whatever the calls say, since every call is refused until then. A market's feed is stale when
 * its latest stored candle closed longer ago than the shortest stored timeframe and a grace
 * ({@link SystemHealthProperties#feedGrace()}): a healthy feed closes no candle more often than that.
 *
 * <p>{@code lastSuccessAt} is held per instance while the failures are shared in the database, so with more than one
 * instance the panel may differ by the instance that answers.
 *
 * <p>Rule: UC-46; NSF-01 to NSF-04 (ingestion); T-017 (Binance IP ban).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemMonitorServiceImpl implements SystemMonitorService {

    /** How far back a failure counts against a service's status. */
    static final Duration RECENT = Duration.ofMinutes(15);

    /** How long a failure stays in the system log after it was last seen (NSF-17). */
    static final Duration KEPT = Duration.ofDays(90);

    private static final ZoneId ZONE = ZoneId.of(AdminMetricSource.ZONE);

    private final SystemEventStore store;
    private final ExternalCallTracker calls;
    private final List<AdminMetricSource> sources;
    private final SystemHealthProperties properties;
    private final SystemEventRecorder recorder;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SystemEventResponse> events(
            SystemEventCategory category,
            SystemEventSource source,
            LocalDate from,
            LocalDate to,
            Integer page,
            Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        if (from != null && to != null && from.isAfter(to)) {
            throw new FieldValidationException("the period starts after it ends", Map.of("from", "MSG15"));
        }
        SystemEventFilter filter = new SystemEventFilter(
                category,
                source,
                from == null ? null : from.atStartOfDay(ZONE).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant());
        long total = store.count(filter);
        List<SystemEventResponse> items = total == 0
                ? List.of()
                : store.search(filter, request.getOffset(), request.getPageSize()).stream()
                        .map(SystemMonitorServiceImpl::response)
                        .toList();
        return new PageResponse<>(items, request.getPageNumber() + 1, request.getPageSize(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public SystemHealthResponse health() {
        Instant now = clock.instant();
        Map<SystemEventSource, SourceFailures> failures = store.failuresBySource(now.minus(Duration.ofDays(1)));
        Map<SystemEventSource, Instant> answers = calls.snapshot();
        Map<String, Object> figures = new TreeMap<>();
        sources.forEach(source -> figures.putAll(source.health(now)));
        Map<String, Instant> bans = instants(figures.get("binanceBans"));

        List<SystemHealthResponse.Service> services = new ArrayList<>();
        for (SystemEventSource source : SystemEventSource.values()) {
            if (!source.external()) {
                continue;
            }
            SourceFailures failed = failures.get(source);
            Instant lastFailure = failed == null ? null : failed.lastFailureAt();
            Instant lastAnswer = answers.get(source);
            Instant bannedUntil = source == SystemEventSource.BINANCE_REST
                    ? bans.values().stream().max(Instant::compareTo).orElse(null)
                    : null;
            ServiceStatus status =
                    bannedUntil != null ? ServiceStatus.DOWN : status(source, lastAnswer, lastFailure, now);
            services.add(new SystemHealthResponse.Service(
                    source,
                    status,
                    lastAnswer,
                    lastFailure,
                    failed == null ? 0 : failed.failuresSince(),
                    failed == null ? null : failed.lastMessage(),
                    bannedUntil));
        }

        List<SystemHealthResponse.Feed> feeds = new ArrayList<>();
        Map<String, Instant> latest = instants(figures.get("latestCandle"));
        Duration staleAfter = staleAfter(figures.get("shortestTimeframe"), properties.feedGrace());
        for (String market : List.of("SPOT", "FUTURES")) {
            Instant close = latest.get(market);
            Long lag = close == null
                    ? null
                    : Math.max(0, Duration.between(close, now).toSeconds());
            feeds.add(new SystemHealthResponse.Feed(market, close, lag, stale(close, now, staleAfter)));
        }
        return new SystemHealthResponse(
                now,
                (int) RECENT.toMinutes(),
                (int) staleAfter.toMinutes(),
                services,
                feeds,
                recorder.droppedCount(),
                recorder.lostAtShutdownCount());
    }

    @Override
    @Transactional
    public int deleteExpired() {
        return store.deleteLastSeenBefore(clock.instant().minus(KEPT));
    }

    /**
     * The status rule of the class comment. A source of many independent endpoints (the news sites) is DOWN only when
     * none of them answered within {@link #RECENT}: one feed refusing while the others answer is DEGRADED.
     */
    static ServiceStatus status(SystemEventSource source, Instant lastAnswer, Instant lastFailure, Instant now) {
        Instant since = now.minus(RECENT);
        boolean recentFailure = lastFailure != null && !lastFailure.isBefore(since);
        if (recentFailure) {
            boolean answeredAfter = lastAnswer != null && lastAnswer.isAfter(lastFailure);
            boolean answeredRecently = lastAnswer != null && !lastAnswer.isBefore(since);
            return answeredAfter || (source.manyEndpoints() && answeredRecently)
                    ? ServiceStatus.DEGRADED
                    : ServiceStatus.DOWN;
        }
        return lastAnswer != null ? ServiceStatus.UP : ServiceStatus.UNKNOWN;
    }

    /** The shortest stored timeframe the market module reports, plus the grace; the grace alone without one. */
    static Duration staleAfter(Object shortestTimeframe, Duration grace) {
        return shortestTimeframe instanceof Duration timeframe ? timeframe.plus(grace) : grace;
    }

    /** Whether nothing is stored, or the latest candle closed longer ago than {@code staleAfter}. */
    static boolean stale(Instant latestClose, Instant now, Duration staleAfter) {
        return latestClose == null || Duration.between(latestClose, now).compareTo(staleAfter) > 0;
    }

    private static Map<String, Instant> instants(Object figure) {
        Map<String, Instant> values = new TreeMap<>();
        if (figure instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (value instanceof Instant at) {
                    values.put(String.valueOf(key), at);
                }
            });
        }
        return values;
    }

    private static SystemEventResponse response(SystemEventRow row) {
        return new SystemEventResponse(
                row.id(),
                row.category(),
                row.source(),
                row.operation(),
                row.errorType(),
                row.message(),
                row.reference(),
                row.occurrences(),
                row.firstSeenAt(),
                row.lastSeenAt());
    }
}
