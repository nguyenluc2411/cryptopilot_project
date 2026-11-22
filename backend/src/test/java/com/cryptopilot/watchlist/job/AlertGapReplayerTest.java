package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.config.AlertReplayProperties;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * T-111: the missed 1-minute prices of a lost stream or a restart are fetched and replayed through the engine.
 *
 * <p>Invariants: (1) a reconnection replays each pair with alerts from the minute of its newest price; (2) a restart
 * replays the whole window; (3) nothing older than the window is fetched; (4) a refusal of the exchange fires nothing
 * and stops that pair; (5) the pages are walked oldest first until the exchange has nothing newer; (6) a pair whose
 * book holds no alert is never fetched; (7) switched off, nothing is fetched.
 *
 * <p>Rule: NSF-06, BR-20; D-87.
 */
class AlertGapReplayerTest {

    private static final Instant T0 = Instant.parse("2026-11-22T08:00:20Z");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000d111");

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final AlertTriggerService triggers = mock(AlertTriggerService.class);
    private final MarketApi market = mock(MarketApi.class);
    private AlertEngine engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void T111_aReconnection_replaysFromTheMinuteOfTheNewestPrice_andFiresLate() {
        PriceAlert alert = crossAbove("100");
        startEngine(alert);
        engine.onMinuteKline(live("99", T0));
        clock.set(T0.plus(Duration.ofMinutes(6)));
        Instant gapMinute = T0.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, gapMinute))
                .thenReturn(MinuteKlineBatch.of(List.of(
                        closed(gapMinute, "99"),
                        closed(gapMinute.plusSeconds(60), "100.5"),
                        closed(gapMinute.plusSeconds(120), "101"))));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, gapMinute.plusSeconds(180)))
                .thenReturn(MinuteKlineBatch.of(List.of()));
        when(triggers.tryTrigger(any())).thenReturn(true);

        replayer(true).onStreamReconnected(new MarketStreamReconnected(MarketType.SPOT));

        ArgumentCaptor<PriceAlertHit> hit = ArgumentCaptor.forClass(PriceAlertHit.class);
        verify(triggers, times(1)).tryTrigger(hit.capture());
        assertThat(hit.getValue().alert().alertId()).isEqualTo(alert.alertId());
        assertThat(hit.getValue().observedValue()).isEqualByComparingTo("100.5");
        assertThat(hit.getValue().at()).isEqualTo(clock.instant());
    }

    @Test
    void T111_aRestart_replaysTheWholeWindow() {
        startEngine(crossAbove("100"));
        clock.set(T0.plus(Duration.ofHours(1)));
        Instant windowStart =
                clock.instant().minus(Duration.ofMinutes(15)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, windowStart))
                .thenReturn(MinuteKlineBatch.of(
                        List.of(closed(windowStart, "98"), closed(windowStart.plusSeconds(60), "102"))));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, windowStart.plusSeconds(120)))
                .thenReturn(MinuteKlineBatch.of(List.of()));
        when(triggers.tryTrigger(any())).thenReturn(true);

        replayer(true).replayMarket(MarketType.SPOT, true);

        verify(triggers, times(1)).tryTrigger(any());
    }

    @Test
    void T111_aGapLongerThanTheWindow_isReplayedFromTheWindowsStartOnly() {
        startEngine(crossAbove("100"));
        engine.onMinuteKline(live("99", T0));
        clock.set(T0.plus(Duration.ofHours(2)));
        Instant windowStart =
                clock.instant().minus(Duration.ofMinutes(15)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(market.closedMinuteKlines(any(), any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));

        replayer(true).replayMarket(MarketType.SPOT, false);

        verify(market).closedMinuteKlines(MarketType.SPOT, PAIR, windowStart);
        verify(market, never())
                .closedMinuteKlines(
                        eq(MarketType.SPOT), eq(PAIR), eq(T0.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)));
    }

    @Test
    void T111_aRefusalOfTheExchange_firesNothing() {
        startEngine(crossAbove("100"));
        engine.onMinuteKline(live("99", T0));
        clock.set(T0.plus(Duration.ofMinutes(5)));
        when(market.closedMinuteKlines(any(), any(), any()))
                .thenReturn(MinuteKlineBatch.refusedUntil(clock.instant().plusSeconds(60)));

        replayer(true).replayMarket(MarketType.SPOT, false);

        verify(market, times(1)).closedMinuteKlines(any(), any(), any());
        verify(triggers, never()).tryTrigger(any());
    }

    @Test
    void T111_aBookWithoutAlerts_orAnotherMarket_isNotFetched() {
        startEngine();
        engine.onMinuteKline(live("99", T0));
        clock.set(T0.plus(Duration.ofMinutes(5)));

        replayer(true).replayMarket(MarketType.SPOT, false);
        startEngineAgainWith(crossAbove("100"));
        replayer(true).replayMarket(MarketType.FUTURES, false);

        verify(market, never()).closedMinuteKlines(any(), any(), any());
    }

    @Test
    void T111_switchedOff_nothingIsReplayed() {
        startEngine(crossAbove("100"));
        engine.onMinuteKline(live("99", T0));

        AlertGapReplayer off = replayer(false);
        off.onStreamReconnected(new MarketStreamReconnected(MarketType.SPOT));
        off.onReady();

        verify(market, never()).closedMinuteKlines(any(), any(), any());
    }

    @Test
    void T111_candlesStillOpenOrClosingAfterNow_areNotReplayed() {
        startEngine(crossAbove("100"));
        engine.onMinuteKline(live("99", T0));
        clock.set(T0.plus(Duration.ofMinutes(3)));
        Instant gapMinute = T0.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        List<MinuteKline> page = new ArrayList<>();
        page.add(closed(gapMinute, "99"));
        page.add(new MinuteKline(
                MarketType.SPOT,
                PAIR,
                gapMinute.plusSeconds(60),
                new BigDecimal("101"),
                new BigDecimal("101"),
                new BigDecimal("101"),
                false,
                gapMinute.plusSeconds(119)));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, gapMinute)).thenReturn(MinuteKlineBatch.of(page));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, gapMinute.plusSeconds(120)))
                .thenReturn(MinuteKlineBatch.of(List.of()));

        replayer(true).replayMarket(MarketType.SPOT, false);

        verify(triggers, never()).tryTrigger(any());
    }

    private void startEngine(PriceAlert... alerts) {
        when(triggers.activePriceAlerts()).thenReturn(List.of(alerts));
        engine = new AlertEngine(triggers, engineProperties(), clock, idleThreads());
        engine.start();
    }

    private void startEngineAgainWith(PriceAlert alert) {
        engine.stop();
        startEngine(alert);
    }

    private AlertGapReplayer replayer(boolean enabled) {
        AlertReplayProperties properties = new AlertReplayProperties(enabled, Duration.ofMinutes(15), Duration.ZERO, 5);
        return new AlertGapReplayer(engine, market, properties, clock, inline());
    }

    private static AlertEngineProperties engineProperties() {
        return new AlertEngineProperties(
                true,
                1,
                100,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }

    /** Runs the task at once on the calling thread; the thread returned does nothing. */
    private static ThreadFactory inline() {
        return task -> {
            task.run();
            return Thread.ofVirtual().unstarted(() -> {});
        };
    }

    private static ThreadFactory idleThreads() {
        return ignored -> Thread.ofVirtual().unstarted(() -> {});
    }

    private static MinuteKline live(String close, Instant at) {
        BigDecimal price = new BigDecimal(close);
        return new MinuteKline(
                MarketType.SPOT,
                PAIR,
                at.truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
                price,
                price,
                price,
                false,
                at);
    }

    private static MinuteKline closed(Instant openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        return new MinuteKline(
                MarketType.SPOT,
                PAIR,
                openTime,
                price,
                price,
                price,
                true,
                openTime.plusSeconds(60).minusMillis(1));
    }

    private static PriceAlert crossAbove(String threshold) {
        return new PriceAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                ConditionOperator.CROSS_ABOVE,
                new BigDecimal(threshold),
                TriggerMode.EVERY_TIME,
                5,
                null,
                true,
                false,
                false,
                0,
                null,
                null,
                1);
    }
}
