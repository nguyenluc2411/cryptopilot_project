package com.cryptopilot.notification.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.config.MarketBroadcastProperties;
import com.cryptopilot.notification.service.MarketNotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

/**
 * The general notice of a market incident to every other Trader (D-158): written {@code delay} after an announced
 * pause and after its reopening, never for a pause never announced, nothing while off, and a failure stops nothing.
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketBroadcastJobTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");
    private static final UUID INCIDENT = UUID.randomUUID();

    private final MarketNotificationService notifications = mock(MarketNotificationService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final MarketPauseAnnounced announced = new MarketPauseAnnounced(
            MarketType.SPOT, INCIDENT, MarketHealthState.PAUSED, IncidentReason.STREAM_LOST, NOW, null);

    @Test
    void D158_theGeneralNotices_areWrittenAfterTheDelay() {
        MarketBroadcastJob job = job(true);

        job.on(announced);
        job.on(new MarketTradingResumed(MarketType.SPOT, INCIDENT, NOW, NOW.plusSeconds(60), true));

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), eq(NOW.plusSeconds(30)));
        tasks.getAllValues().forEach(Runnable::run);
        verify(notifications).broadcastPause(announced);
        verify(notifications).broadcastResume(any());
    }

    @Test
    void D158_aPauseNeverAnnounced_tellsNoOneElse_andNothingRunsWhileOff() {
        job(true).on(new MarketTradingResumed(MarketType.SPOT, INCIDENT, NOW, NOW.plusSeconds(20), false));
        job(false).on(announced);

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void D158_aFailedBroadcast_stopsNothing() {
        when(notifications.broadcastPause(any())).thenThrow(new IllegalStateException("database down"));
        job(true).on(announced);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();
    }

    private MarketBroadcastJob job(boolean enabled) {
        return new MarketBroadcastJob(
                notifications,
                new MarketBroadcastProperties(enabled, Duration.ofSeconds(30)),
                scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
