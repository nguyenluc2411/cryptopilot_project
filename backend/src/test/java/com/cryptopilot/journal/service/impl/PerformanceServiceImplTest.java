package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.journal.dto.request.PerformanceFilterRequest;
import com.cryptopilot.journal.dto.response.PerformanceSummaryResponse;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.repository.PerformanceQueryRepository;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.trading.StrategyCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The default end of a period at an instant when UTC and UTC+7 are on different days.
 *
 * <p>Rule: UC-25; CR-08; D-58.
 */
class PerformanceServiceImplTest {

    /** Still the 29th in UTC, already the 30th in UTC+7. */
    private static final Instant LATE = Instant.parse("2026-09-29T17:30:00Z");

    private static final PerformanceFilterRequest NO_FILTERS =
            new PerformanceFilterRequest(null, null, null, null, null, null);

    @Test
    void UC25_defaultEnd_isTomorrowInUtcPlus7NotInUtc() {
        PerformanceQueryRepository statistics = mock(PerformanceQueryRepository.class);
        when(statistics.counts(any()))
                .thenReturn(
                        new PerformanceCounts(0, 0, 0, 0, null, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        PerformanceServiceImpl service = new PerformanceServiceImpl(
                statistics,
                mock(EntitlementApi.class),
                mock(MarketApi.class),
                mock(StrategyCatalog.class),
                Clock.fixed(LATE, ZoneOffset.UTC));

        PerformanceSummaryResponse summary = service.summary(UUID.randomUUID(), NO_FILTERS);

        assertThat(summary.to()).isEqualTo(LocalDate.parse("2026-10-01"));
        ArgumentCaptor<PerformanceFilter> filter = ArgumentCaptor.forClass(PerformanceFilter.class);
        verify(statistics).counts(filter.capture());
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
    }
}
