package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.dto.response.ReportResponse;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dashboard and the report gather every module's metrics; a metric name two modules both report is a defect the
 * service refuses, instead of one module's figure silently replacing the other's.
 *
 * <p>Rule: UC-41, UC-49, UC-50.
 */
class DashboardServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T05:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");

    @Test
    void theMetricsOfEverySource_areGathered() {
        DashboardServiceImpl service =
                new DashboardServiceImpl(List.of(source("newUsers", "3"), source("newPosts", "5")), CLOCK);

        assertThat(service.dashboard(DAY, DAY).indicators()).containsOnlyKeys("newUsers", "newPosts");
    }

    @Test
    void aMetricTwoSourcesReport_isRefused_inTheDashboard() {
        DashboardServiceImpl service =
                new DashboardServiceImpl(List.of(source("newUsers", "3"), source("newUsers", "4")), CLOCK);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.dashboard(DAY, DAY))
                .withMessageContaining("newUsers");
    }

    @Test
    void aMetricTwoSourcesReport_isRefused_inTheReport() {
        DashboardServiceImpl service =
                new DashboardServiceImpl(List.of(source("newUsers", "3"), source("newUsers", "4")), CLOCK);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.report(DAY, DAY, ReportGrouping.DAY))
                .withMessageContaining("newUsers");
    }

    /** A WEEK report from a Wednesday to a Tuesday: two weeks, each cut to the days of the period. */
    @Test
    void UC41_aCutWeek_saysWhichDaysItCounts() {
        DashboardServiceImpl service = new DashboardServiceImpl(List.of(source("newUsers", "1")), CLOCK);

        List<ReportResponse.Row> rows = service.report(
                        LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-20"), ReportGrouping.WEEK)
                .rows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).period()).isEqualTo("2026-10-12");
        assertThat(rows.get(0).coveredFrom()).isEqualTo("2026-10-14");
        assertThat(rows.get(0).coveredTo()).isEqualTo("2026-10-18");
        assertThat(rows.get(1).period()).isEqualTo("2026-10-19");
        assertThat(rows.get(1).coveredFrom()).isEqualTo("2026-10-19");
        assertThat(rows.get(1).coveredTo()).isEqualTo("2026-10-20");
    }

    /** A MONTH report inside one month: one row, labelled with the 1st, counting only the period. */
    @Test
    void UC41_aCutMonth_saysWhichDaysItCounts() {
        DashboardServiceImpl service = new DashboardServiceImpl(List.of(source("newUsers", "1")), CLOCK);

        ReportResponse.Row row = service.report(
                        LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-20"), ReportGrouping.MONTH)
                .rows()
                .getFirst();

        assertThat(row.period()).isEqualTo("2026-10-01");
        assertThat(row.coveredFrom()).isEqualTo("2026-10-10");
        assertThat(row.coveredTo()).isEqualTo("2026-10-20");
    }

    private static AdminMetricSource source(String metric, String value) {
        return new AdminMetricSource() {
            @Override
            public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
                return Map.of(metric, new BigDecimal(value));
            }

            @Override
            public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
                return Map.of(metric, Map.of(DAY, new BigDecimal(value)));
            }
        };
    }
}
