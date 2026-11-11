package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.journal.dto.request.PerformanceFilterRequest;
import com.cryptopilot.journal.dto.response.PerformanceBreakdownResponse;
import com.cryptopilot.journal.dto.response.PerformanceSummaryResponse;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.model.PerformanceGroup;
import com.cryptopilot.journal.repository.PerformanceQueryRepository;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.trading.StrategyCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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

    @Test
    void UC25_byCoin_labelsAPairThatCannotBeFoundWithNull() {
        UUID pair = UUID.randomUUID();
        PerformanceCounts counts =
                new PerformanceCounts(1, 1, 0, 0, BigDecimal.ONE, BigDecimal.ONE, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        PerformanceQueryRepository statistics = mock(PerformanceQueryRepository.class);
        when(statistics.byPair(any())).thenReturn(List.of(new PerformanceGroup(pair.toString(), counts, null, null)));
        MarketApi market = mock(MarketApi.class);
        when(market.pairListings(any())).thenReturn(List.of());
        PerformanceServiceImpl service = new PerformanceServiceImpl(
                statistics,
                mock(EntitlementApi.class),
                market,
                mock(StrategyCatalog.class),
                Clock.fixed(LATE, ZoneOffset.UTC));

        List<PerformanceBreakdownResponse> rows = service.byCoin(UUID.randomUUID(), NO_FILTERS);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).key()).isEqualTo(pair.toString());
        assertThat(rows.get(0).label()).isNull();
    }
}
