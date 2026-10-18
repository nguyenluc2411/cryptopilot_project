package com.cryptopilot.common.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.config.OutboxProperties;
import com.cryptopilot.common.service.OutboxService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.TaskScheduler;

/**
 * The outbox processor of T-056: scheduled at the configured interval when enabled, nothing when disabled; a run
 * retries and then cleans, and a failing run does not escape into the scheduler.
 *
 * <p>Rule: NSF-15; ADR-010.
 */
class OutboxProcessorJobTest {

    private final OutboxService outbox = mock(OutboxService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void NSF15_enabled_runsAtOnceAndThenEveryInterval() {
        job(true).start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMinutes(1)));
    }

    @Test
    void NSF15_disabled_schedulesNothing() {
        job(false).start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void NSF15_aRun_retriesThenDeletesWhatExpired() {
        job(true).run();

        InOrder order = inOrder(outbox);
        order.verify(outbox).retryFailedDeliveries();
        order.verify(outbox).deleteExpired();
    }

    @Test
    void NSF15_aFailingRun_isContained() {
        when(outbox.retryFailedDeliveries()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> job(true).run()).doesNotThrowAnyException();
    }

    private OutboxProcessorJob job(boolean enabled) {
        return new OutboxProcessorJob(
                outbox,
                scheduler,
                new OutboxProperties(
                        enabled,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        3,
                        Duration.ofMinutes(5),
                        Duration.ofHours(24)));
    }
}
