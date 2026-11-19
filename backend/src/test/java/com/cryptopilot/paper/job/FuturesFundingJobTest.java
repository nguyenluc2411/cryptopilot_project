package com.cryptopilot.paper.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketHealth;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperFundingProperties;
import com.cryptopilot.paper.service.impl.FuturesFunding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * How far back a funding run looks (TR-24; D-158, 6.2): its lookback, or further for an incident that just ended
 * further back, so the settlements due while Futures was closed are all taken once it is open.
 *
 * <p>Rule: TR-24; D-158.
 */
class FuturesFundingJobTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");

    private final FuturesFunding funding = mock(FuturesFunding.class);
    private final MarketHealth health = mock(MarketHealth.class);
    private FuturesFundingJob job;

    @BeforeEach
    void job() {
        when(health.tradingOpen(MarketType.FUTURES)).thenReturn(true);
        when(funding.owed(any(), any())).thenReturn(List.of());
        job = new FuturesFundingJob(
                funding,
                health,
                mock(TaskScheduler.class),
                new PaperFundingProperties(true, Duration.ofMinutes(1), Duration.ofDays(2)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void TR24_withNoIncident_aRunLooksBackItsLookback() {
        when(health.catchUpFrom(MarketType.FUTURES)).thenReturn(Optional.empty());

        job.run();

        verify(funding).owed(NOW.minus(Duration.ofDays(2)), NOW);
    }

    @Test
    void D158_afterAnIncidentLongerThanTheLookback_aRunLooksBackToItsStart() {
        when(health.catchUpFrom(MarketType.FUTURES)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(5))));

        job.run();

        verify(funding).owed(NOW.minus(Duration.ofDays(5)), NOW);
    }

    @Test
    void D158_afterAShortIncident_aRunKeepsItsLookback() {
        when(health.catchUpFrom(MarketType.FUTURES)).thenReturn(Optional.of(NOW.minus(Duration.ofHours(1))));

        job.run();

        verify(funding).owed(NOW.minus(Duration.ofDays(2)), NOW);
    }
}
