package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.trading.config.PlanExpiryProperties;
import com.cryptopilot.trading.service.PlanExpiryService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** The schedule of NSF-09, and a run that fails. */
class PlanExpiryJobTest {

    private final PlanExpiryService expiry = mock(PlanExpiryService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void NSF09_whenEnabled_theExpiryRunsEveryInterval() {
        job(true).start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMinutes(1)));
    }

    @Test
    void NSF09_whenDisabled_nothingIsScheduled() {
        job(false).start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void NSF09_aRun_expiresOneBatch_andAFailureIsLeftForTheNextRun() {
        when(expiry.expireDue(50)).thenReturn(2).thenThrow(new IllegalStateException("database down"));

        job(true).run();
        assertThatNoException().isThrownBy(() -> job(true).run());

        verify(expiry, times(2)).expireDue(50);
    }

    @Test
    void NSF09_aRunWithNothingDue_isQuiet() {
        when(expiry.expireDue(50)).thenReturn(0);

        assertThatNoException().isThrownBy(() -> job(true).run());
    }

    private PlanExpiryJob job(boolean enabled) {
        return new PlanExpiryJob(expiry, scheduler, new PlanExpiryProperties(enabled, Duration.ofMinutes(1), 50));
    }
}
