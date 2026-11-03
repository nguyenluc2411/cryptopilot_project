package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.config.MarketHubProperties;
import com.cryptopilot.market.service.MarketBroadcastService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * A new subscriber to an overview gets it within moments, not at the next scheduled push; subscriptions arriving
 * together share one push, a storm of them pushes at most once every two seconds, and other topics change nothing.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 9.
 */
class MarketHubJobTest {

    private final MarketBroadcastService broadcast = mock(MarketBroadcastService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T06:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @Test
    void aSubscriptionToAnOverview_pushesTheOverviewsSoon_onceForSubscriptionsTogether() {
        MarketHubJob job = job(true);

        job.onSubscribe(subscribe("/topic/overview.SPOT"));
        job.onSubscribe(subscribe("/topic/overview.FUTURES"));

        ArgumentCaptor<Runnable> push = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, times(1)).schedule(push.capture(), at.capture());
        assertThat(at.getValue()).isEqualTo(now.get().plus(MarketHubJob.SUBSCRIBE_SETTLE));
        push.getValue().run();
        verify(broadcast).pushOverviews();

        now.set(now.get().plus(MarketHubJob.SUBSCRIBE_MIN_GAP));
        job.onSubscribe(subscribe("/topic/overview.SPOT"));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void aSubscribeStorm_pushesAtMostOnceEveryMinGap() {
        MarketHubJob job = job(true);
        ArgumentCaptor<Runnable> push = ArgumentCaptor.forClass(Runnable.class);

        for (int i = 0; i < 20; i++) {
            job.onSubscribe(subscribe("/topic/overview.SPOT"));
            now.set(now.get().plusMillis(50));
            if (i == 5) {
                // The queued push has run: only the gap holds the others back.
                verify(scheduler).schedule(push.capture(), any(Instant.class));
                push.getValue().run();
            }
        }
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));

        now.set(now.get().plus(MarketHubJob.SUBSCRIBE_MIN_GAP));
        job.onSubscribe(subscribe("/topic/overview.FUTURES"));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void aRefusedSchedule_doesNotBlockTheNextSubscription() {
        org.mockito.Mockito.when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new org.springframework.core.task.TaskRejectedException("shutting down"))
                .thenReturn(null);
        MarketHubJob job = job(true);

        job.onSubscribe(subscribe("/topic/overview.SPOT"));
        job.onSubscribe(subscribe("/topic/overview.SPOT"));

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void anotherTopic_orTopicsTurnedOff_pushNothing() {
        job(true).onSubscribe(subscribe("/topic/ticker.SPOT.BTCUSDT"));
        job(true).onSubscribe(subscribe(null));
        job(false).onSubscribe(subscribe("/topic/overview.SPOT"));

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(broadcast, never()).pushOverviews();
    }

    @Test
    void start_schedulesTheThreePushes_whenEnabled() {
        List<Duration> rates = new ArrayList<>();
        job(false).start();
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));

        job(true).start();
        ArgumentCaptor<Duration> rate = ArgumentCaptor.forClass(Duration.class);
        verify(scheduler, times(3)).scheduleAtFixedRate(any(Runnable.class), rate.capture());
        rates.addAll(rate.getAllValues());
        assertThat(rates).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(100));
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(2)));
    }

    private MarketHubJob job(boolean enabled) {
        return new MarketHubJob(
                broadcast,
                scheduler,
                new MarketHubProperties(enabled, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(100)),
                clock);
    }

    private static SessionSubscribeEvent subscribe(String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        if (destination != null) {
            headers.setDestination(destination);
        }
        headers.setSessionId("s1");
        return new SessionSubscribeEvent(
                new Object(), MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
    }
}
