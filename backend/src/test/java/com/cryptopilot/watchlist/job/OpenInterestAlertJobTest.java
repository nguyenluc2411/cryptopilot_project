package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.OpenInterestSampled;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.OpenInterestAlertService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The job only hands the reading on: not where the alert engine is off, and a failure stays in the job.
 *
 * <p>Rule: NSF-06; SRS 3.4.4; Q-45.
 */
class OpenInterestAlertJobTest {

    private static final OpenInterestSampled SAMPLE =
            new OpenInterestSampled(UUID.randomUUID(), "BTCUSDT", Instant.parse("2026-10-14T10:05:00Z"));

    private final OpenInterestAlertService service = mock(OpenInterestAlertService.class);

    @Test
    void NSF06_whereTheEngineRuns_theReadingIsEvaluated() {
        new OpenInterestAlertJob(service, properties(true)).onOpenInterestSampled(SAMPLE);

        verify(service).evaluate(SAMPLE);
    }

    @Test
    void NSF06_whereTheEngineIsOff_nothingIsEvaluated() {
        new OpenInterestAlertJob(service, properties(false)).onOpenInterestSampled(SAMPLE);

        verify(service, never()).evaluate(any());
    }

    @Test
    void NSF06_aFailure_doesNotReachNsf04() {
        when(service.evaluate(any())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> new OpenInterestAlertJob(service, properties(true)).onOpenInterestSampled(SAMPLE))
                .doesNotThrowAnyException();
    }

    private static AlertEngineProperties properties(boolean enabled) {
        return new AlertEngineProperties(
                enabled,
                1,
                10,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }
}
