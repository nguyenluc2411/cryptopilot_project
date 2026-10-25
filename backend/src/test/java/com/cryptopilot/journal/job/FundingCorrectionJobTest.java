package com.cryptopilot.journal.job;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.config.FundingCorrectionProperties;
import com.cryptopilot.journal.service.FundingCorrectionService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** The schedule of the funding correction, and a run that fails. */
class FundingCorrectionJobTest {

    private final FundingCorrectionService corrections = mock(FundingCorrectionService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void BR37_whenEnabled_theCorrectionRunsAtItsInterval() {
        job(true).start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMinutes(5)));
    }

    @Test
    void BR37_whenDisabled_nothingIsScheduled() {
        job(false).start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void BR37_aRun_correctsOneBatch() {
        job(true).run();

        verify(corrections).correctIncompleteFunding(25);
    }

    @Test
    void BR37_aRunThatFails_isLeftForTheNext() {
        when(corrections.correctIncompleteFunding(25)).thenThrow(new IllegalStateException("database down"));

        assertThatNoException().isThrownBy(() -> job(true).run());
    }

    @Test
    void BR37_aRunThatCorrectsSome_logsThem() {
        when(corrections.correctIncompleteFunding(25)).thenReturn(3);

        assertThatNoException().isThrownBy(() -> job(true).run());
    }

    private FundingCorrectionJob job(boolean enabled) {
        return new FundingCorrectionJob(
                corrections, scheduler, new FundingCorrectionProperties(enabled, Duration.ofMinutes(5), 25));
    }
}
