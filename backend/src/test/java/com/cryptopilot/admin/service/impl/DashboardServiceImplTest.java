package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.admin.AdminMetricSource;
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
 * <p>Rule: UC-49, UC-50.
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
