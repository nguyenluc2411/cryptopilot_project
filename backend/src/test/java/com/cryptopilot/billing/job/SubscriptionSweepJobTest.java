package com.cryptopilot.billing.job;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.config.SubscriptionSweepProperties;
import com.cryptopilot.billing.service.SubscriptionLifecycleService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** The NSF-14 run is scheduled only when enabled, expires before it reminds, and survives a failure. */
class SubscriptionSweepJobTest {

    private final SubscriptionLifecycleService lifecycle = mock(SubscriptionLifecycleService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private SubscriptionSweepJob job(boolean enabled) {
        return new SubscriptionSweepJob(
                lifecycle, scheduler, new SubscriptionSweepProperties(enabled, "0 0 * * * *", Duration.ofDays(3), 500));
    }

    @Test
    void NSF14_theRun_isScheduledHourly_onlyWhenEnabled() {
        job(false).start();
        verify(scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));

        job(true).start();
        verify(scheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void NSF14_aRun_expiresFirst_thenReminds_andSurvivesAFailure() {
        when(lifecycle.expireEnded()).thenReturn(1).thenThrow(new IllegalStateException("db down"));
        when(lifecycle.remindEnding()).thenReturn(2);
        SubscriptionSweepJob job = job(true);

        job.run();
        assertThatNoException().isThrownBy(job::run);

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).expireEnded();
        order.verify(lifecycle).remindEnding();
    }
}
