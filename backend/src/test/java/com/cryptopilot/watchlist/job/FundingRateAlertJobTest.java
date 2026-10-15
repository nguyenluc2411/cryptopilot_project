package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.FundingRateAlertService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * The FUNDING_RATE evaluation is scheduled once per throttle where the alert engine runs, and a failed run does not
 * stop the next one.
 *
 * <p>Rule: NSF-06; SRS 3.4.4.
 */
class FundingRateAlertJobTest {

    private final FundingRateAlertService service = mock(FundingRateAlertService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void SRS344_whereTheEngineRuns_theEvaluationRunsOncePerThrottle() {
        new FundingRateAlertJob(service, scheduler, properties(true)).start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(1)));
    }

    @Test
    void NSF06_whereTheEngineIsOff_nothingIsScheduled() {
        new FundingRateAlertJob(service, scheduler, properties(false)).start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void NSF06_aFailedRun_isLogged_andDoesNotEscape() {
        when(service.evaluate()).thenThrow(new IllegalStateException("cache down"));

        assertThatCode(() -> new FundingRateAlertJob(service, scheduler, properties(true)).run())
                .doesNotThrowAnyException();
        verify(service).evaluate();
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
