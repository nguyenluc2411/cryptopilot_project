package com.cryptopilot.billing.job;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.config.ReconcileProperties;
import com.cryptopilot.billing.service.OrderReconciliationService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** The NSF-13 run is scheduled only when enabled, and a failed run is left for the next one. */
class OrderReconciliationJobTest {

    private final OrderReconciliationService reconciliation = mock(OrderReconciliationService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private OrderReconciliationJob job(boolean enabled) {
        return new OrderReconciliationJob(
                reconciliation,
                scheduler,
                new ReconcileProperties(enabled, Duration.ofMinutes(5), Duration.ofMinutes(5), 50));
    }

    @Test
    void NSF13_theRun_isScheduledEveryInterval_onlyWhenEnabled() {
        job(false).start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        job(true).start();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMinutes(5)));
    }

    @Test
    void NSF13_aRun_reconcilesTheDueOrders_andSurvivesAFailure() {
        when(reconciliation.reconcileDue()).thenReturn(2).thenReturn(0).thenThrow(new IllegalStateException("db down"));
        OrderReconciliationJob job = job(true);

        job.run();
        job.run();
        assertThatNoException().isThrownBy(job::run);

        verify(reconciliation, times(3)).reconcileDue();
    }
}
