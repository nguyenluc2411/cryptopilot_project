package com.cryptopilot.paper.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.service.FuturesOrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** The GTD orders are expired every minute, one at a time, a failing one left for the next run (TR-44). */
class FuturesGoodTillJobTest {

    private static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");

    private final FuturesOrderService orders = mock(FuturesOrderService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final PaperMatchingProperties matching = mock(PaperMatchingProperties.class);

    @Test
    void TR44_theRunsAreScheduled_onlyWhileTheEngineIsEnabled() {
        when(matching.enabled()).thenReturn(false);
        job().start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        when(matching.enabled()).thenReturn(true);
        job().start();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    }

    @Test
    void TR44_anOrderThatFails_doesNotStopTheOthers() {
        UUID failing = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        UUID due = UUID.randomUUID();
        when(orders.dueGoodTill(NOW)).thenReturn(List.of(failing, ended, due));
        when(orders.expireGoodTill(failing, NOW)).thenThrow(new IllegalStateException("lock timeout"));
        when(orders.expireGoodTill(due, NOW)).thenReturn(true);

        job().run();

        verify(orders).expireGoodTill(ended, NOW);
        verify(orders).expireGoodTill(due, NOW);
    }

    private FuturesGoodTillJob job() {
        return new FuturesGoodTillJob(orders, scheduler, matching, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
