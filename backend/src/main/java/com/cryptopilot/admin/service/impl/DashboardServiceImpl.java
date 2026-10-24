package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.dto.response.DashboardResponse;
import com.cryptopilot.admin.dto.response.ReportResponse;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import com.cryptopilot.admin.service.DashboardService;
import com.cryptopilot.common.exception.FieldValidationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCR-33. Each {@link AdminMetricSource} answers for its own module; this turns the period into instants, asks them
 * all in one read-only transaction, and lays the series out as rows with every bucket of the period, so a day without
 * any order reads 0 rather than being missing from the export.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1; CR-01.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** A period without bounds is this many business days, ending today. */
    static final int DEFAULT_DAYS = 30;

    /** The most rows a report holds: a year of days. */
    static final int MAX_ROWS = 366;

    /** The columns every report has, in this order; a package's revenue follows {@code revenue}. */
    private static final List<String> COLUMN_ORDER =
            List.of("newUsers", "paidOrders", "revenue", "newPosts", "newComments", "aiQuestions");

    private static final ZoneId ZONE = ZoneId.of(AdminMetricSource.ZONE);

    private final List<AdminMetricSource> sources;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(LocalDate from, LocalDate to) {
        LocalDate last = to == null ? LocalDate.now(clock.withZone(ZONE)) : to;
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1) : from;
        requireOrdered(first, last);
        Map<String, Object> indicators = new TreeMap<>();
        Instant now = clock.instant();
        for (AdminMetricSource source : sources) {
            merge(indicators, source.indicators(start(first), start(last.plusDays(1)), now));
        }
        return new DashboardResponse(first, last, indicators);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponse report(LocalDate from, LocalDate to, ReportGrouping groupBy) {
        ReportGrouping grouping = groupBy == null ? ReportGrouping.DAY : groupBy;
        LocalDate last = to == null ? LocalDate.now(clock.withZone(ZONE)) : to;
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1) : from;
        requireOrdered(first, last);
        List<LocalDate> buckets = new ArrayList<>();
        for (LocalDate bucket = bucketOf(first, grouping); !bucket.isAfter(last); bucket = next(bucket, grouping)) {
            buckets.add(bucket);
            if (buckets.size() > MAX_ROWS) {
                throw new FieldValidationException(
                        "a report holds at most " + MAX_ROWS + " rows; group the period by week or month",
                        Map.of("from", "MSG15"));
            }
        }
        Map<String, Map<LocalDate, BigDecimal>> series = new HashMap<>();
        for (AdminMetricSource source : sources) {
            merge(series, source.series(start(first), start(last.plusDays(1)), grouping));
        }
        List<String> columns = series.keySet().stream()
                .sorted(Comparator.comparingInt(DashboardServiceImpl::rank).thenComparing(Comparator.naturalOrder()))
                .toList();
        List<ReportResponse.Row> rows = buckets.stream()
                .map(bucket -> new ReportResponse.Row(
                        bucket,
                        bucket.isBefore(first) ? first : bucket,
                        lastDayOf(bucket, grouping).isAfter(last) ? last : lastDayOf(bucket, grouping),
                        columns.stream()
                                .collect(Collectors.toMap(
                                        column -> column,
                                        column -> series.get(column).getOrDefault(bucket, BigDecimal.ZERO),
                                        (a, b) -> a,
                                        LinkedHashMap::new))))
                .toList();
        return new ReportResponse(first, last, grouping, columns, rows);
    }

    @Override
    public String csv(ReportResponse report) {
        StringBuilder csv = new StringBuilder("period,coveredFrom,coveredTo");
        report.columns().forEach(column -> csv.append(',').append(column));
        csv.append("\r\n");
        for (ReportResponse.Row row : report.rows()) {
            csv.append(row.period())
                    .append(',')
                    .append(row.coveredFrom())
                    .append(',')
                    .append(row.coveredTo());
            for (String column : report.columns()) {
                csv.append(',')
                        .append(row.values().get(column).stripTrailingZeros().toPlainString());
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    /** A known column's place; a package's revenue right after the total, anything else last. */
    private static int rank(String column) {
        if (column.startsWith("revenue:")) {
            return COLUMN_ORDER.indexOf("revenue") * 2 + 1;
        }
        int index = COLUMN_ORDER.indexOf(column);
        return index < 0 ? Integer.MAX_VALUE : index * 2;
    }

    private static void requireOrdered(LocalDate first, LocalDate last) {
        if (first.isAfter(last)) {
            throw new FieldValidationException("the period starts after it ends", Map.of("from", "MSG15"));
        }
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /** The first business day of the group a day falls in, as PostgreSQL's {@code date_trunc} cuts it. */
    static LocalDate bucketOf(LocalDate day, ReportGrouping grouping) {
        return switch (grouping) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }

    /** The last day of the day, week or month a bucket starts. */
    private static LocalDate lastDayOf(LocalDate bucket, ReportGrouping grouping) {
        return next(bucket, grouping).minusDays(1);
    }

    private static LocalDate next(LocalDate bucket, ReportGrouping grouping) {
        return switch (grouping) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }

    /**
     * Adds one source's metrics. Each module names its own metrics, so a name two sources share is a defect, reported
     * rather than letting one source silently replace the other's figure.
     */
    private static <V> void merge(Map<String, V> into, Map<String, V> from) {
        from.forEach((name, value) -> {
            if (into.putIfAbsent(name, value) != null) {
                throw new IllegalStateException("two metric sources both report " + name);
            }
        });
    }
}
