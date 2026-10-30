package com.cryptopilot.paper.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.service.FuturesPositionWarningService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** The held-position warnings run every interval, one Trader at a time, a failing Trader left for the next run. */
class FuturesPositionWarningJobTest {

    private static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");
    private static final PaperFuturesProperties FUTURES = new PaperFuturesProperties(
            Duration.ofSeconds(5),
            new BigDecimal("0.01"),
            new BigDecimal("0.05"),
            new BigDecimal("20"),
            Duration.ofSeconds(30),
            new BigDecimal("0.8"),
            Duration.ofMinutes(30),
            new BigDecimal("5"),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(1));

    private final FuturesPositionWarningService warnings = mock(FuturesPositionWarningService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final PaperMatchingProperties matching = mock(PaperMatchingProperties.class);

    @Test
    void TR27_theRunsAreScheduled_onlyWhileTheEngineIsEnabled() {
        when(matching.enabled()).thenReturn(false);
        job().start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        when(matching.enabled()).thenReturn(true);
        job().start();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMinutes(1)));
    }

    @Test
    void TR27_aTraderThatFails_doesNotStopTheOthers() {
        UUID failing = UUID.randomUUID();
        UUID warned = UUID.randomUUID();
        when(warnings.holders()).thenReturn(List.of(failing, warned));
        when(warnings.warn(failing, NOW)).thenThrow(new IllegalStateException("no mark"));
        when(warnings.warn(warned, NOW)).thenReturn(2);

        job().run();

        verify(warnings).warn(warned, NOW);
    }

    private FuturesPositionWarningJob job() {
        return new FuturesPositionWarningJob(warnings, scheduler, matching, FUTURES, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
