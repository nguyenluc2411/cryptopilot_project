package com.cryptopilot.market.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.fallback.OkxProperties;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.dto.response.FallbackPricesResponse;
import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.DisplayPriceService;
import com.cryptopilot.market.service.MarketHealthService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.TaskScheduler;

/**
 * The polls of the fallback prices (D-158): read and pushed for a market paused or recovering, forgotten for one live
 * or under maintenance, scheduled only with the fallback, the monitor and the streams on.
 *
 * <p>Rule: D-158.
 */
class FallbackPriceJobTest {

    private final DisplayPriceService prices = mock(DisplayPriceService.class);
    private final MarketHealthService health = mock(MarketHealthService.class);
    private final SimpMessageSendingOperations broker = mock(SimpMessageSendingOperations.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final BinanceStreamProperties streams = mock(BinanceStreamProperties.class);
    private final FallbackPricesResponse futures =
            new FallbackPricesResponse(MarketType.FUTURES, DisplaySource.OKX, null, List.of());

    @BeforeEach
    void futuresPaused() {
        when(streams.enabled()).thenReturn(true);
        when(health.state(MarketType.SPOT)).thenReturn(MarketHealthState.MAINTENANCE);
        when(health.state(MarketType.FUTURES)).thenReturn(MarketHealthState.PAUSED);
        when(prices.prices(MarketType.FUTURES)).thenReturn(futures);
    }

    @Test
    void D158_aLostMarket_isReadAndPushed_andAnotherForgotten() {
        job(true, true).poll();

        verify(prices).refresh(MarketType.FUTURES);
        verify(broker).convertAndSend("/topic/market.fallback.FUTURES", futures);
        verify(prices).clear(MarketType.SPOT);
        verify(prices, never()).refresh(MarketType.SPOT);

        when(health.state(MarketType.FUTURES)).thenReturn(MarketHealthState.RECOVERING);
        doThrow(new IllegalStateException("broker down")).when(broker).convertAndSend(anyString(), any(Object.class));
        job(true, true).poll();
    }

    @Test
    void D158_thePolls_runOnlyWithTheFallbackTheMonitorAndTheStreams() {
        job(false, true).start();
        job(true, false).start();
        when(streams.enabled()).thenReturn(false);
        job(true, true).start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        when(streams.enabled()).thenReturn(true);
        job(true, true).start();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(3)));
    }

    private FallbackPriceJob job(boolean fallback, boolean monitor) {
        return new FallbackPriceJob(
                prices,
                health,
                broker,
                scheduler,
                new OkxProperties(
                        fallback,
                        URI.create("https://www.okx.com"),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(3),
                        new BigDecimal("0.05"),
                        Map.of()),
                new MarketHealthProperties(
                        monitor, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
                streams);
    }
}
