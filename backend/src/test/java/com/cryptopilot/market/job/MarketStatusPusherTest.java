package com.cryptopilot.market.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.dto.response.MarketStatusResponse;
import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.MarketStatusService;
import com.cryptopilot.market.service.impl.MarketLiveness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.TaskScheduler;

/**
 * The pushes of the markets' data health to {@code /topic/market.status} (D-159): on every change, every five seconds
 * while a market recovers and never otherwise, and a broker that fails stops nothing.
 *
 * <p>Rule: NSF-03; D-159.
 */
class MarketStatusPusherTest {

    private final MarketStatusService status = mock(MarketStatusService.class);
    private final MarketHealthService health = mock(MarketHealthService.class);
    private final MarketLiveness liveness = mock(MarketLiveness.class);
    private final SimpMessageSendingOperations broker = mock(SimpMessageSendingOperations.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final BinanceStreamProperties streams = mock(BinanceStreamProperties.class);
    private final MarketStatusResponse current = new MarketStatusResponse(List.of());
    private final MarketStatusPusher pusher = new MarketStatusPusher(
            status,
            health,
            liveness,
            broker,
            scheduler,
            new MarketHealthProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
            streams);

    @BeforeEach
    void allLive() {
        when(streams.enabled()).thenReturn(true);
        when(status.status()).thenReturn(current);
        when(health.state(any())).thenReturn(MarketHealthState.LIVE);
        when(liveness.silentSymbols(any())).thenReturn(List.of());
    }

    /** E5: the pairs whose data is lost on an open market are pushed when they change, and only then. */
    @Test
    void D159_theLostPairsOfAnOpenMarket_arePushedWhenTheyChange() {
        pusher.pushWhileRecovering();
        verify(broker, never()).convertAndSend(anyString(), any(Object.class));

        when(liveness.silentSymbols(MarketType.SPOT)).thenReturn(List.of("BTCUSDT"));
        pusher.pushWhileRecovering();
        pusher.pushWhileRecovering();
        verify(broker, times(1)).convertAndSend(MarketStatusPusher.TOPIC, current);

        when(liveness.silentSymbols(MarketType.SPOT)).thenReturn(List.of());
        pusher.pushWhileRecovering();
        verify(broker, times(2)).convertAndSend(MarketStatusPusher.TOPIC, current);
    }

    @Test
    void D159_aChange_isPushedToTheTopic() {
        pusher.onChanged(new MarketHealthChanged(MarketType.FUTURES, MarketHealthState.LIVE, MarketHealthState.PAUSED));

        verify(broker).convertAndSend(MarketStatusPusher.TOPIC, current);
    }

    @Test
    void D159_whileAMarketRecovers_theStatusIsPushedAgain_andNeverOtherwise() {
        pusher.start();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(5)));

        pusher.pushWhileRecovering();
        verify(broker, never()).convertAndSend(anyString(), any(Object.class));

        when(health.state(MarketType.SPOT)).thenReturn(MarketHealthState.RECOVERING);
        pusher.pushWhileRecovering();
        verify(broker).convertAndSend(MarketStatusPusher.TOPIC, current);
    }

    @Test
    void D159_withoutTheMonitor_nothingIsScheduled() {
        new MarketStatusPusher(
                        status,
                        health,
                        liveness,
                        broker,
                        scheduler,
                        new MarketHealthProperties(
                                false, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
                        streams)
                .start();

        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    }

    @Test
    void D159_aBrokerThatFails_stopsNothing() {
        doThrow(new IllegalStateException("broker down")).when(broker).convertAndSend(anyString(), any(Object.class));

        pusher.onChanged(
                new MarketHealthChanged(MarketType.SPOT, MarketHealthState.LIVE, MarketHealthState.RECONNECTING));
    }
}
