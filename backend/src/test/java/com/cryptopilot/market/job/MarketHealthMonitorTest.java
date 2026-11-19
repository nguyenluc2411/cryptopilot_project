package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketRecoveryCheck;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.RestOutage;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.impl.MarketLiveness;
import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

/**
 * The monitor's tick (D-158): the signals it hands the service for each market, the reason it gives for a pause, the
 * modules it asks only while a market recovers, and the markets it leaves alone when the streams are off.
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketHealthMonitorTest {

    private static final Instant START = Instant.parse("2026-11-19T08:00:00Z");
    private static final MarketHealthProperties ON =
            new MarketHealthProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15));

    private final MutableTestClock clock = new MutableTestClock(START);
    private final MarketHealthService health = mock(MarketHealthService.class);
    private final BinanceRestClient exchange = mock(BinanceRestClient.class);
    private final BinanceStreamProperties streams = mock(BinanceStreamProperties.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final List<String> asked = new ArrayList<>();
    private MarketLiveness liveness;

    @BeforeEach
    void liveMarkets() {
        liveness = new MarketLiveness(ON, clock);
        when(health.state(any())).thenReturn(MarketHealthState.LIVE);
        when(exchange.outage(any())).thenReturn(Optional.empty());
        when(streams.enabled()).thenReturn(true);
    }

    @Test
    void D158_eachTick_handsEachMarketItsStreamAndRestSignals() {
        clock.advance(Duration.ofSeconds(3));
        liveness.record(MarketType.SPOT, clock.instant());
        clock.advance(Duration.ofSeconds(2));
        when(exchange.outage(BinanceVenue.USD_M_FUTURES))
                .thenReturn(Optional.of(
                        new RestOutage(BinanceClientException.Kind.BANNED, START.plus(Duration.ofHours(1)))));

        monitor(List.of()).tick();

        ArgumentCaptor<HealthSignals> spot = ArgumentCaptor.forClass(HealthSignals.class);
        verify(health).evaluate(eq(MarketType.SPOT), spot.capture(), eq(IncidentReason.STREAM_LOST));
        assertThat(spot.getValue().lastLiveAt()).isEqualTo(START.plusSeconds(3));
        assertThat(spot.getValue().restAvailable()).isTrue();
        assertThat(spot.getValue().maintenance()).isFalse();
        ArgumentCaptor<HealthSignals> futures = ArgumentCaptor.forClass(HealthSignals.class);
        verify(health).evaluate(eq(MarketType.FUTURES), futures.capture(), eq(IncidentReason.IP_BANNED));
        assertThat(futures.getValue().lastLiveAt())
                .as("never streamed: the start")
                .isEqualTo(START);
        assertThat(futures.getValue().restAvailable()).isFalse();
    }

    @Test
    void D158_anOpenCircuit_isGivenAsTheRestBeingUnavailable() {
        when(exchange.outage(BinanceVenue.SPOT))
                .thenReturn(
                        Optional.of(new RestOutage(BinanceClientException.Kind.CIRCUIT_OPEN, START.plusSeconds(30))));

        monitor(List.of()).tick();

        verify(health).evaluate(eq(MarketType.SPOT), any(), eq(IncidentReason.REST_UNAVAILABLE));
    }

    /** I6: a recovering market is recovered only once every module has caught up since its last live data. */
    @Test
    void D158_aRecoveringMarket_isRecovered_onlyOnceEveryModuleHasCaughtUp() {
        Instant lastLive = START.minusSeconds(600);
        when(health.state(MarketType.FUTURES)).thenReturn(MarketHealthState.RECOVERING);
        when(health.openIncident(MarketType.FUTURES)).thenReturn(Optional.of(incident(lastLive)));
        boolean[] engineDone = {false};
        MarketRecoveryCheck paper = (market, since) -> {
            asked.add("paper " + market + " " + since);
            return engineDone[0];
        };
        MarketRecoveryCheck plans = (market, since) -> {
            asked.add("plans " + market + " " + since);
            return true;
        };
        MarketHealthMonitor monitor = monitor(List.of(paper, plans));

        monitor.tick();
        engineDone[0] = true;
        monitor.tick();

        ArgumentCaptor<HealthSignals> futures = ArgumentCaptor.forClass(HealthSignals.class);
        verify(health, org.mockito.Mockito.times(2))
                .evaluate(eq(MarketType.FUTURES), futures.capture(), eq(IncidentReason.STREAM_LOST));
        assertThat(futures.getAllValues())
                .extracting(HealthSignals::recoveryDone)
                .containsExactly(false, true);
        assertThat(asked)
                .as("asked about the recovering market only, from its last live data")
                .containsExactly("paper FUTURES " + lastLive, "paper FUTURES " + lastLive, "plans FUTURES " + lastLive);
    }

    @Test
    void D158_aMarketThatFails_doesNotStopTheOther() {
        when(exchange.outage(BinanceVenue.SPOT)).thenThrow(new IllegalStateException("down"));

        monitor(List.of()).tick();

        verify(health).evaluate(eq(MarketType.FUTURES), any(), any());
        verify(health, never()).evaluate(eq(MarketType.SPOT), any(), any());
    }

    @Test
    void D158_theMonitor_runsOnlyWithTheStreams() {
        when(streams.enabled()).thenReturn(false);
        monitor(List.of()).start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        new MarketHealthMonitor(
                        health,
                        liveness,
                        exchange,
                        List.of(),
                        new MarketHealthProperties(
                                false, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
                        streams,
                        scheduler)
                .start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        when(streams.enabled()).thenReturn(true);
        monitor(List.of()).start();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(MarketHealthMonitor.INTERVAL));
    }

    private MarketHealthMonitor monitor(List<MarketRecoveryCheck> checks) {
        return new MarketHealthMonitor(health, liveness, exchange, checks, ON, streams, scheduler);
    }

    private static MarketIncident incident(Instant lastLive) {
        return new MarketIncident(
                UUID.randomUUID(),
                MarketType.FUTURES,
                IncidentKind.OUTAGE,
                IncidentReason.STREAM_LOST,
                lastLive,
                lastLive.plusSeconds(30),
                START.minusSeconds(20),
                null,
                null,
                null);
    }
}
