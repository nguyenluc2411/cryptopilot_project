package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarkPrice;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.event.FuturesAccountChanged;
import com.cryptopilot.paper.event.PaperOrderAwaitingTrigger;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.model.CrossAccount;
import com.cryptopilot.paper.model.LiquidationLevel;
import com.cryptopilot.paper.model.ReplayedCandle;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.LimitFillMode;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.support.MutableTestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The paper matching engine around its book, with the storage side and the exchange mocked: what fills, on which
 * market trade or candle, at which time and from which source, when the watermark moves, and how missed candles are
 * replayed.
 */
class PaperMatchingWorkerTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c001");
    private static final Instant PLACED = Instant.parse("2026-10-07T08:00:30Z");
    private static final Instant MINUTE_0 = Instant.parse("2026-10-07T08:00:00Z");
    private static final Instant MINUTE_1 = MINUTE_0.plusSeconds(60);
    private static final Instant MINUTE_2 = MINUTE_0.plusSeconds(120);
    private static final Instant MINUTE_5 = MINUTE_0.plusSeconds(300);
    private static final long WAIT = Duration.ofSeconds(5).toMillis();
    private static final PaperFuturesProperties FUTURES_PROPERTIES = new PaperFuturesProperties(
            Duration.ofSeconds(5),
            new BigDecimal("0.01"),
            new BigDecimal("0.05"),
            new BigDecimal("20"),
            Duration.ofSeconds(30),
            new BigDecimal("0.8"),
            Duration.ofMinutes(30),
            new BigDecimal("5"),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(1));

    private final PaperMatchingService matching = mock(PaperMatchingService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final MutableTestClock clock = new MutableTestClock(MINUTE_2);
    private final List<Thread> started = new CopyOnWriteArrayList<>();
    private final ThreadFactory recording = task -> {
        Thread thread = Thread.ofVirtual().unstarted(task);
        started.add(thread);
        return thread;
    };

    private PaperMatchingWorker worker;

    @BeforeEach
    void noCandlesToReplayByDefault() {
        when(market.closedMinuteKlines(any(), any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
        when(market.closedMarkPriceKlines(any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
        when(matching.watermark(any(), any())).thenReturn(Optional.empty());
        when(matching.markWatermark(any())).thenReturn(Optional.empty());
        when(matching.fill(any(), any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (worker != null) {
            worker.stop();
            assertThat(worker.isRunning()).isFalse();
        }
        for (Thread thread : started) {
            thread.join(WAIT);
        }
    }

    @Test
    void TR02_whenDisabled_nothingStarts_andNothingIsMatched() {
        worker = worker(properties(false, false, Duration.ofMinutes(10)));

        worker.start();
        worker.onMinuteKline(kline(MINUTE_1, "90", "110", true));
        worker.onRested(new PaperOrderRested(buy("100")));
        worker.onCanceled(new PaperOrderCanceled(UUID.randomUUID()));

        assertThat(started).isEmpty();
        assertThat(worker.isRunning()).isFalse();
        verifyNoInteractions(matching, market);
    }

    @Test
    void TR02_theBookStartsFromTheWorkingOrders_andAClosedCandleFillsThemAtItsOpenTime() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE, null);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
    }

    @Test
    void TR02_formingUpdatesOfACandle_areMerged_andFillWhatTheyReached_withoutMovingTheWatermark() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "101", "102", false));
        worker.onMinuteKline(kline(MINUTE_1, "99.5", "102", false));

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE, null);
        verify(matching, after(200).never()).advanceWatermark(any(), any(), any());
    }

    @Test
    void D77_aCandleThatOpenedBeforeTheOrderWasPlaced_doesNotFillIt() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_0, "90", "110", true));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_0);
        verify(matching, never()).fill(any(), any(), any(), any());
    }

    @Test
    void TR02_anOrderThatRests_isMatched_andACancelledOneIsNot() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        RestingOrder cancelled = buy("100");
        RestingOrder kept = buy("95");

        worker.onRested(new PaperOrderRested(cancelled));
        worker.onCanceled(new PaperOrderCanceled(cancelled.orderId()));
        worker.onRested(new PaperOrderRested(kept));
        worker.onMinuteKline(kline(MINUTE_1, "90", "110", true));

        verify(matching, timeout(WAIT)).fill(kept, MINUTE_1, FillSource.LIVE, null);
        verify(matching, never()).fill(eq(cancelled), any(), any(), any());
    }

    @Test
    void TR02_aFillThatFails_isTriedAgainOnThePairsNextRange_andHoldsTheWatermarkMeanwhile() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE, null))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE, null);
        worker.onMinuteKline(kline(MINUTE_2, "120", "130", false));

        verify(matching, timeout(WAIT).times(2)).fill(order, MINUTE_1, FillSource.LIVE, null);
        verify(matching, never()).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
    }

    @Test
    void TR02_aCancelWhileAFillWaitsForItsRetry_dropsTheRetry() {
        RestingOrder order = buy("100");
        RestingOrder keeper = buy("10");
        when(matching.restingOrders()).thenReturn(List.of(order, keeper));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE, null))
                .thenThrow(new IllegalStateException("database down"));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE, null);
        worker.onCanceled(new PaperOrderCanceled(order.orderId()));
        worker.onMinuteKline(kline(MINUTE_2, "90", "130", true));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_2);
        verify(matching, never()).fill(order, MINUTE_2, FillSource.LIVE, null);
        verify(matching).fill(order, MINUTE_1, FillSource.LIVE, null);
    }

    @Test
    void TR02_aFillFailingPastItsDeadline_isGivenUp_theOrderWaitsAgain_andTheWatermarkStaysHeld() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE, null)).thenThrow(new IllegalStateException("bug"));
        worker = started(properties(true, false, Duration.ZERO));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE, null);
        worker.onMinuteKline(kline(MINUTE_2, "99", "110", true));

        verify(matching, timeout(WAIT)).fill(order, MINUTE_2, FillSource.LIVE, null);
        verify(matching, after(200).never()).advanceWatermark(any(), any(), any());
    }

    @Test
    void QT6_atStart_aPairWithOrders_isReplayedFromTheMinuteOfItsOldestOrder_withReplayedFills() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(
                        List.of(kline(MINUTE_0, "90", "110", true), kline(MINUTE_1, "99", "110", true))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).fill(eq(order), eq(MINUTE_1), eq(FillSource.REPLAY), any());
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
        verify(market, timeout(WAIT)).closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_2);
        verify(matching, never()).fill(eq(order), eq(MINUTE_0), eq(FillSource.REPLAY), any());
    }

    @Test
    void Q52_aReplay_prefetchesTheQuoteRatesOfItsCandles_beforeTheirFills() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(
                        List.of(kline(MINUTE_0, "101", "110", true), kline(MINUTE_1, "99", "110", true))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).fill(eq(order), eq(MINUTE_1), eq(FillSource.REPLAY), any());
        InOrder sequence = inOrder(matching);
        sequence.verify(matching).prefetchQuoteRates(MarketType.SPOT, PAIR, MINUTE_0, MINUTE_1);
        sequence.verify(matching).fill(eq(order), eq(MINUTE_1), eq(FillSource.REPLAY), any());
    }

    @Test
    void Q52_aPrefetchThatFails_neitherStopsNorDelaysTheReplay() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(List.of(kline(MINUTE_1, "99", "110", true))));
        doThrow(new IllegalStateException("exchange down"))
                .when(matching)
                .prefetchQuoteRates(any(), any(), any(), any());
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).fill(eq(order), eq(MINUTE_1), eq(FillSource.REPLAY), any());
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
    }

    @Test
    void Q51_aReplayedCandle_passesItsOpenToTheStopItTriggers() {
        TriggerOrder stop = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_BELOW,
                new BigDecimal("100"),
                null,
                null,
                PLACED);
        when(matching.triggerOrders()).thenReturn(List.of(stop));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(List.of(new MinuteKline(
                        MarketType.SPOT,
                        PAIR,
                        MINUTE_1,
                        new BigDecimal("90"),
                        new BigDecimal("96"),
                        new BigDecimal("92"),
                        true,
                        MINUTE_1.plusSeconds(59),
                        new BigDecimal("95")))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT))
                .trigger(stop, MINUTE_1, new BigDecimal("100"), FillSource.REPLAY, new BigDecimal("95"));
    }

    @Test
    void QT6_aReplayStartsAfterTheWatermark_andNeverBeyondItsWindow() {
        RestingOrder recent = buy("100");
        RestingOrder old = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                UUID.randomUUID(),
                OrderSide.SELL,
                new BigDecimal("100"),
                MINUTE_0.minus(Duration.ofDays(3)));
        when(matching.restingOrders()).thenReturn(List.of(recent, old));
        when(matching.watermark(MarketType.SPOT, PAIR)).thenReturn(Optional.of(MINUTE_0));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(market, timeout(WAIT)).closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_1);
        verify(market, timeout(WAIT))
                .closedMinuteKlines(MarketType.SPOT, old.pairId(), MINUTE_2.minus(Duration.ofHours(24)));
    }

    @Test
    void QT6_aReplayTheExchangeRefusedOrThatFailed_isTriedAgain() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.refusedUntil(MINUTE_0))
                .thenThrow(new IllegalStateException("exchange down"))
                .thenReturn(MinuteKlineBatch.of(List.of(kline(MINUTE_1, "99", "110", true))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).fill(eq(order), eq(MINUTE_1), eq(FillSource.REPLAY), any());
    }

    @Test
    void QT6_aPairWithOrdersWhoseLiveCandlesSkipAMinute_isReplayedFromTheMissingOne() {
        worker = started(properties(true, true, Duration.ofMinutes(10)));
        RestingOrder order = buy("50");
        worker.onRested(new PaperOrderRested(order));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        worker.onMinuteKline(kline(MINUTE_5, "99", "110", true));

        verify(market, timeout(WAIT)).closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_2);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
        verify(matching, never()).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_5);
    }

    @Test
    void TR02_aPairWithoutOrders_movesNoWatermark() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        worker.onMinuteKline(kline(MINUTE_2, "99", "110", false));

        verify(matching, after(200).never()).advanceWatermark(any(), any(), any());
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void TR02_aWatermarkThatCannotBeWritten_doesNotStopTheEngine() {
        RestingOrder first = buy("100");
        RestingOrder second = buy("90");
        when(matching.restingOrders()).thenReturn(List.of(first, second));
        when(matching.fill(first, MINUTE_1, FillSource.LIVE, null)).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("database down"))
                .when(matching)
                .advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        worker.onMinuteKline(kline(MINUTE_2, "89", "110", true));

        verify(matching, timeout(WAIT)).fill(second, MINUTE_2, FillSource.LIVE, null);
    }

    @Test
    void TR05_aTradeThroughAWaitingOrder_fillsItAtOnce_atTheTradesTime_withoutMovingTheWatermark() {
        RestingOrder buy = buy("100");
        RestingOrder sell = sell("110");
        RestingOrder away = buy("90");
        when(matching.restingOrders()).thenReturn(List.of(buy, sell, away));
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        Instant low = MINUTE_1.plusMillis(1_250);
        Instant high = MINUTE_1.plusMillis(2_500);

        worker.onTrade(trade("99.99", low));
        worker.onTrade(trade("110.01", high));

        verify(matching, timeout(WAIT)).fill(buy, low, FillSource.LIVE, null);
        verify(matching, timeout(WAIT)).fill(sell, high, FillSource.LIVE, null);
        verify(matching, after(200).never()).fill(eq(away), any(), any(), any());
        verify(matching, never()).advanceWatermark(any(), any(), any());
    }

    @Test
    void D77_aTradeReadBeforeTheOrderWasTracked_fillsItOnceTracked_butNotOnePlacedAfterThatTrade() {
        RestingOrder marker = sell("90");
        when(matching.restingOrders()).thenReturn(List.of(marker));
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        Instant traded = PLACED.plusSeconds(2);
        RestingOrder placedBefore = buy("99");
        RestingOrder placedAfter = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("99"),
                traded.plusMillis(1));
        published(placedBefore);
        published(placedAfter);
        worker.onTrade(trade("98.5", traded));
        verify(matching, timeout(WAIT)).fill(marker, traded, FillSource.LIVE, null);

        worker.onRested(new PaperOrderRested(placedAfter));
        worker.onRested(new PaperOrderRested(placedBefore));

        verify(matching, timeout(WAIT)).fill(placedBefore, traded, FillSource.LIVE, null);
        verify(matching, after(200).never()).fill(eq(placedAfter), any(), any(), any());
    }

    @Test
    void D77_aStopWatchedAfterATradeReadBeforeIt_isTriggeredByThatTrade() {
        RestingOrder marker = sell("90");
        when(matching.restingOrders()).thenReturn(List.of(marker));
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        Instant traded = PLACED.plusSeconds(2);
        TriggerOrder stop = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_BELOW,
                new BigDecimal("99"),
                null,
                null,
                PLACED);
        TriggerOrder later = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_BELOW,
                new BigDecimal("99"),
                null,
                null,
                traded);
        published(stop);
        published(later);
        worker.onTrade(trade("98.5", traded));
        verify(matching, timeout(WAIT)).fill(marker, traded, FillSource.LIVE, null);

        worker.onAwaitingTrigger(new PaperOrderAwaitingTrigger(later));
        worker.onAwaitingTrigger(new PaperOrderAwaitingTrigger(stop));

        verify(matching, timeout(WAIT)).trigger(stop, traded, new BigDecimal("99"), FillSource.LIVE, null);
        verify(matching, after(200).never()).trigger(eq(later), any(), any(), any(), any());
    }

    @Test
    void D77_theRecentTradesKept_areBoundedByTimeAndByCount() {
        RestingOrder marker = sell("90");
        when(matching.restingOrders()).thenReturn(List.of(marker));
        worker = started(properties(
                true,
                false,
                Duration.ofMinutes(10),
                LimitFillMode.CROSS,
                new PaperMatchingProperties.RecentTrades(Duration.ofSeconds(10), 2)));
        RestingOrder firstMarker = sell("119");
        RestingOrder buy = buy("99");
        RestingOrder lateBuy = buy("98");
        RestingOrder lateSell = sell("130.5");
        List.of(firstMarker, buy, lateBuy, lateSell).forEach(this::published);
        worker.onTrade(trade("98", PLACED.plusSeconds(1)));
        verify(matching, timeout(WAIT)).fill(marker, PLACED.plusSeconds(1), FillSource.LIVE, null);
        // Past the window of the first trade, which is dropped.
        worker.onTrade(trade("120", PLACED.plusSeconds(20)));
        worker.onRested(new PaperOrderRested(firstMarker));
        verify(matching, timeout(WAIT)).fill(firstMarker, PLACED.plusSeconds(20), FillSource.LIVE, null);
        worker.onRested(new PaperOrderRested(buy));
        // Three more trades: only the last two are kept.
        worker.onTrade(trade("97", PLACED.plusSeconds(21)));
        worker.onTrade(trade("130", PLACED.plusSeconds(22)));
        worker.onTrade(trade("131", PLACED.plusSeconds(23)));
        verify(matching, timeout(WAIT)).fill(buy, PLACED.plusSeconds(21), FillSource.LIVE, null);
        worker.onRested(new PaperOrderRested(lateBuy));
        worker.onRested(new PaperOrderRested(lateSell));

        verify(matching, timeout(WAIT)).fill(lateSell, PLACED.plusSeconds(23), FillSource.LIVE, null);
        verify(matching, after(200).never()).fill(eq(lateBuy), any(), any(), any());
    }

    /** TR-05: a pair with no order tracked and none about to be keeps no trade, so a late order catches up nothing. */
    @Test
    void TR05_aPairWithoutOrders_keepsNoRecentTrades() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        Instant traded = PLACED.plusSeconds(2);
        RestingOrder late = buy("99");

        worker.onTrade(trade("98.5", traded));
        worker.onRested(new PaperOrderRested(late));

        verify(matching, after(300).never()).fill(eq(late), any(), any(), any());
    }

    /**
     * D-77: a pair with an order published but not tracked yet keeps its trades meanwhile, even without any other
     * order, and the order catches up with them once tracked.
     */
    @Test
    void D77_aPairWithAnOrderInFlight_keepsItsTradesUntilItIsTracked() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        Instant traded = PLACED.plusSeconds(2);
        RestingOrder inFlight = buy("99");
        published(inFlight);

        worker.onTrade(trade("98.5", traded));
        worker.onRested(new PaperOrderRested(inFlight));

        verify(matching, timeout(WAIT)).fill(inFlight, traded, FillSource.LIVE, null);
    }

    /**
     * TR-05: the count bound drops the oldest recent trades after the window, the newest stay, and a trade dropped while
     * still within the window is logged.
     */
    @Test
    void TR05_aRecentTradeDroppedWithinTheWindow_isLogged_andTheNewestAreKept() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(PaperMatchingWorker.class);
        logs.start();
        logger.addAppender(logs);
        try {
            worker = started(properties(
                    true,
                    false,
                    Duration.ofMinutes(10),
                    LimitFillMode.CROSS,
                    new PaperMatchingProperties.RecentTrades(Duration.ofSeconds(10), 2)));
            RestingOrder lateBuy = buy("98");
            RestingOrder lateSell = sell("130.5");
            published(lateBuy);
            published(lateSell);

            worker.onTrade(trade("97", PLACED.plusSeconds(1)));
            worker.onTrade(trade("130", PLACED.plusSeconds(2)));
            worker.onTrade(trade("131", PLACED.plusSeconds(3)));
            worker.onRested(new PaperOrderRested(lateBuy));
            worker.onRested(new PaperOrderRested(lateSell));

            verify(matching, timeout(WAIT)).fill(lateSell, PLACED.plusSeconds(3), FillSource.LIVE, null);
            verify(matching, after(200).never()).fill(eq(lateBuy), any(), any(), any());
            assertThat(logs.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("recent trades within"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    /** D-77: a placement that rolls back leaves its pair keeping no trade for it. */
    @Test
    void D77_aPlacementThatRollsBack_stopsKeepingTheTradesOfItsPair() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));
        RestingOrder rolledBack = buy("99");
        published(rolledBack);
        worker.onRestedRolledBack(new PaperOrderRested(rolledBack));
        RestingOrder late = buy("99");

        worker.onTrade(trade("98.5", PLACED.plusSeconds(2)));
        worker.onRested(new PaperOrderRested(late));

        verify(matching, after(300).never()).fill(eq(late), any(), any(), any());
    }

    /** TR-05, Q-T6: a fill decided by a replayed candle carries that candle to the storage side. */
    @Test
    void QT6_aReplayedFill_carriesItsCandle() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(List.of(new MinuteKline(
                        MarketType.SPOT,
                        PAIR,
                        MINUTE_1,
                        new BigDecimal("99"),
                        new BigDecimal("103"),
                        new BigDecimal("100"),
                        true,
                        MINUTE_1.plusSeconds(59),
                        new BigDecimal("101")))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT))
                .fill(
                        order,
                        MINUTE_1,
                        FillSource.REPLAY,
                        new ReplayedCandle(new BigDecimal("101"), new BigDecimal("99"), new BigDecimal("103")));
    }

    @Test
    void QT8_aTradeAtTheOrdersOwnPrice_fillsItOnlyWithTouch() {
        RestingOrder crossOrder = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(crossOrder));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("100", MINUTE_1));

        verify(matching, after(200).never()).fill(any(), any(), any(), any());
        worker.stop();

        RestingOrder touchOrder = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(touchOrder));
        worker = started(properties(true, false, Duration.ofMinutes(10), LimitFillMode.TOUCH));

        worker.onTrade(trade("100", MINUTE_1));

        verify(matching, timeout(WAIT)).fill(touchOrder, MINUTE_1, FillSource.LIVE, null);
    }

    @Test
    void D77_aTradeBeforeTheOrderWasPlaced_doesNotFillIt_butTheNextOneDoes() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("99", PLACED.minusMillis(1)));
        verify(matching, after(200).never()).fill(any(), any(), any(), any());
        worker.onTrade(trade("99", PLACED.plusMillis(1)));

        verify(matching, timeout(WAIT)).fill(order, PLACED.plusMillis(1), FillSource.LIVE, null);
    }

    @Test
    void TR05_whileTradesFlow_formingCandlesFillNothing_aClosedOneStillDoes_andSilenceGivesThemBack() {
        RestingOrder byClosed = buy("100");
        RestingOrder bySilence = buy("95");
        when(matching.restingOrders()).thenReturn(List.of(byClosed, bySilence));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("120", MINUTE_1));
        worker.onMinuteKline(kline(MINUTE_1, "99", "120", false));
        verify(matching, after(200).never()).fill(any(), any(), any(), any());

        worker.onMinuteKline(kline(MINUTE_1, "99", "120", true));
        verify(matching, timeout(WAIT)).fill(byClosed, MINUTE_1, FillSource.LIVE, null);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);

        clock.advance(Duration.ofSeconds(5));
        worker.onMinuteKline(kline(MINUTE_2, "94", "120", false));
        verify(matching, timeout(WAIT)).fill(bySilence, MINUTE_2, FillSource.LIVE, null);
    }

    @Test
    void TR05_aFillATradeDecidedThatFails_isTriedAgainOnThePairsNextTrade() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE, null))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("99", MINUTE_1));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE, null);
        worker.onTrade(trade("120", MINUTE_1.plusSeconds(1)));

        verify(matching, timeout(WAIT).times(2)).fill(order, MINUTE_1, FillSource.LIVE, null);
    }

    @Test
    void QT8_queue_anOrderStartsBehindTheDepthAtItsPrice_andFillsOnceTradesThereConsumedIt() {
        when(market.currentDepth(MarketType.SPOT, PAIR))
                .thenReturn(Optional.of(new OrderBookDepth(
                        MarketType.SPOT,
                        PAIR,
                        7,
                        List.of(
                                new OrderBookDepth.PriceLevel(new BigDecimal("80001"), BigDecimal.ONE),
                                new OrderBookDepth.PriceLevel(new BigDecimal("80000"), new BigDecimal("2")),
                                new OrderBookDepth.PriceLevel(new BigDecimal("79990"), BigDecimal.ONE)),
                        List.of(new OrderBookDepth.PriceLevel(new BigDecimal("80002"), BigDecimal.ONE)),
                        MINUTE_1)));
        worker = started(properties(true, false, Duration.ofMinutes(10), LimitFillMode.QUEUE));
        RestingOrder atLevel = buy("80000");
        RestingOrder between = buy("79995");
        worker.onRested(new PaperOrderRested(atLevel));
        worker.onRested(new PaperOrderRested(between));

        worker.onTrade(trade("80000", "1.5", true, MINUTE_1));
        verify(matching, after(200).never()).fill(any(), any(), any(), any());
        worker.onTrade(trade("80000", "1", true, MINUTE_1.plusSeconds(1)));
        verify(matching, timeout(WAIT)).fill(atLevel, MINUTE_1.plusSeconds(1), FillSource.LIVE, null);

        worker.onTrade(trade("79995", "0.001", true, MINUTE_1.plusSeconds(2)));
        verify(matching, timeout(WAIT)).fill(between, MINUTE_1.plusSeconds(2), FillSource.LIVE, null);
    }

    @Test
    void TR05_beforeStartOrWhenDisabled_aTradeIsIgnored() {
        worker = worker(properties(false, false, Duration.ofMinutes(10)));
        worker.start();

        worker.onTrade(trade("1", MINUTE_1));

        assertThat(worker.isIdle()).isTrue();
        verifyNoInteractions(matching);
    }

    /** TR-20: a mark price reaches the conditional orders that watch the mark, and only those. */
    @Test
    void TR20_aMark_triggersTheConditionalOrdersOnTheMark_atItsTime() {
        TriggerOrder onMark = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_BELOW,
                new BigDecimal("95"),
                null,
                null,
                PLACED);
        TriggerOrder onLast = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_BELOW,
                new BigDecimal("95"),
                null,
                null,
                PLACED);
        when(matching.markTriggerOrders()).thenReturn(List.of(onMark));
        when(matching.triggerOrders()).thenReturn(List.of(onLast));
        when(matching.trigger(any(), any(), any(), any(), any())).thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("96"), MINUTE_1));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("94.5"), MINUTE_1.plusSeconds(1)));

        verify(matching, timeout(WAIT))
                .trigger(onMark, MINUTE_1.plusSeconds(1), new BigDecimal("95"), FillSource.LIVE, null);
        verify(matching, never()).trigger(eq(onLast), any(), any(), any(), any());
    }

    /** TR-22: a mark reaching a liquidation level liquidates it; one the database no longer bears out is read again. */
    @Test
    void TR22_aMarkReachingALevel_liquidatesIt_andAStaleLevelIsReadAgain() {
        UUID trader = UUID.randomUUID();
        LiquidationLevel reached =
                new LiquidationLevel(UUID.randomUUID(), trader, PAIR, PositionDirection.LONG, new BigDecimal("95"));
        LiquidationLevel short_ =
                new LiquidationLevel(UUID.randomUUID(), trader, PAIR, PositionDirection.SHORT, new BigDecimal("120"));
        when(matching.liquidationLevels()).thenReturn(List.of(reached, short_));
        when(matching.liquidate(any(), any(), any())).thenReturn(false);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("94"), MINUTE_1));

        verify(matching, timeout(WAIT)).liquidate(reached, new BigDecimal("94"), MINUTE_1);
        verify(matching, timeout(WAIT)).liquidationLevels(trader, PAIR);
        verify(matching, never()).liquidate(eq(short_), any(), any());
    }

    /** TR-21: a trailing stop on the mark follows the peak and triggers on the pull-back of its callback. */
    @Test
    void TR21_aTrailingStopOnTheMark_followsThePeak_andTriggersOnThePullBack() {
        TriggerOrder trailing = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_ABOVE,
                null,
                new BigDecimal("1"),
                null,
                PLACED);
        when(matching.markTriggerOrders()).thenReturn(List.of(trailing));
        when(matching.trigger(any(), any(), any(), any(), any())).thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("100"), MINUTE_1));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("105"), MINUTE_1.plusSeconds(1)));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("104"), MINUTE_1.plusSeconds(2)));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("103.9"), MINUTE_1.plusSeconds(3)));

        verify(matching, timeout(WAIT))
                .trigger(
                        eq(trailing),
                        eq(MINUTE_1.plusSeconds(3)),
                        argThat(price -> price.compareTo(new BigDecimal("103.95")) == 0),
                        any(),
                        any());
    }

    @Test
    void TR02_aSecondStart_doesNothing() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.start();

        assertThat(started).hasSize(2);
    }

    /**
     * TR-42: at start, a pair with a stop on the mark is caught up from the minute it was placed: its mark candles run
     * now, the pair stays caught up until the minute of the first live mark is replayed and two live marks followed.
     */
    @Test
    void TR42_atStart_theMarkCandles_areReplayed_andThePairGoesLive_afterItsMinuteAndTwoMarks() {
        TriggerOrder stop = new TriggerOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                OrderSide.SELL,
                TriggerCondition.AT_OR_BELOW,
                new BigDecimal("95"),
                null,
                null,
                PLACED);
        when(matching.markTriggerOrders()).thenReturn(List.of(stop));
        when(matching.trigger(any(), any(), any(), any(), any())).thenReturn(true);
        when(market.closedMarkPriceKlines(PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(List.of(
                        markKline(MINUTE_0, "100", "101", "99", "100"),
                        markKline(MINUTE_1, "100", "100.5", "94", "96"))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).markCatchingUp(PAIR, true);
        // Below 95 in the second candle: triggered now, on the state of now.
        verify(matching, timeout(WAIT)).trigger(stop, MINUTE_2, new BigDecimal("95"), FillSource.REPLAY, null);
        verify(matching, timeout(WAIT)).advanceMarkWatermark(PAIR, MINUTE_1);

        // The minute of the first live mark is still running: the pair waits for its candle.
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("97"), MINUTE_2.plusSeconds(1)));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("97"), MINUTE_2.plusSeconds(2)));
        verify(matching, after(200).never()).markCatchingUp(PAIR, false);

        Instant minute3 = MINUTE_2.plus(Duration.ofMinutes(1));
        when(market.closedMarkPriceKlines(PAIR, MINUTE_2))
                .thenReturn(MinuteKlineBatch.of(List.of(markKline(MINUTE_2, "96", "97", "96", "97"))));
        clock.set(minute3.plusSeconds(1));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("97"), minute3.plusSeconds(1)));
        verify(matching, timeout(WAIT)).advanceMarkWatermark(PAIR, MINUTE_2);
        verify(matching, after(200).never()).markCatchingUp(PAIR, false);

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("97"), minute3.plusSeconds(2)));
        verify(matching, timeout(WAIT)).markCatchingUp(PAIR, false);
    }

    /**
     * TR-42: live, a mark of a watched pair that comes too long after the previous holds the pair: it is caught up
     * from the minute of the last mark, and the mark held liquidates nothing meanwhile. Marks without a gap close
     * their minute.
     */
    @Test
    void TR42_aGapInTheMarks_startsACatchUp_fromTheMinuteOfTheLastMark() {
        UUID trader = UUID.randomUUID();
        LiquidationLevel level =
                new LiquidationLevel(UUID.randomUUID(), trader, PAIR, PositionDirection.LONG, new BigDecimal("95"));
        when(matching.liquidationLevels(trader, PAIR)).thenReturn(List.of(level));
        worker = started(properties(true, true, Duration.ofMinutes(10)));
        worker.onAccountChanged(new FuturesAccountChanged(trader, PAIR));

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("100"), MINUTE_1.minusSeconds(1)));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("100"), MINUTE_1));
        verify(matching, timeout(WAIT)).advanceMarkWatermark(PAIR, MINUTE_0);

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("94"), MINUTE_1.plusSeconds(10)));

        verify(matching, timeout(WAIT)).markCatchingUp(PAIR, true);
        verify(market, timeout(WAIT).atLeastOnce()).closedMarkPriceKlines(PAIR, MINUTE_1);
        verify(matching, after(200).never()).liquidate(any(), any(), any());
        // The candle of that minute is not out yet: asked for again only when a live mark comes.
        verify(market, atMost(1)).closedMarkPriceKlines(PAIR, MINUTE_1);
    }

    /** TR-42: a mark candle runs open, the extreme nearer the open, the other extreme, close. */
    @Test
    void TR42_aMarkCandle_runsOpen_theNearerExtreme_theOther_andClose() {
        assertThat(PaperMatchingWorker.markPath(markKline(MINUTE_0, "100", "100.5", "94", "96")))
                .extracting(MarkPrice::price)
                .containsExactly(
                        new BigDecimal("100"), new BigDecimal("100.5"), new BigDecimal("94"), new BigDecimal("96"));
        assertThat(PaperMatchingWorker.markPath(markKline(MINUTE_0, "100", "106", "99", "105")))
                .extracting(MarkPrice::price)
                .containsExactly(
                        new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("106"), new BigDecimal("105"));
        assertThat(PaperMatchingWorker.markPath(markKline(MINUTE_0, "100", "106", "99", "105")))
                .extracting(MarkPrice::eventTime)
                .isSorted()
                .allMatch(at -> !at.isBefore(MINUTE_0) && at.isBefore(MINUTE_1));
    }

    /**
     * TR-23: a mark that puts a Trader's cross snapshot at or below its maintenance asks for a step of the liquidation
     * on that pair; the position named on another pair is force-closed in that pair's turn, and the snapshot is read
     * again.
     */
    @Test
    void TR23_aMarkPuttingACrossAccountPastMaintenance_asksForAStep_andForceClosesTheNamedPair() {
        UUID trader = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(matching.crossAccounts()).thenReturn(List.of(crossAccount(trader, other)));
        when(matching.liquidateCross(trader, PAIR, MINUTE_1)).thenReturn(Optional.of(other));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMarkPrice(new MarkPrice(other, new BigDecimal("100"), MINUTE_0));
        // 5,000 + 0.1 × (20,000 − 60,000) = 1,000 is above the maintenance.
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("20000"), MINUTE_0.plusSeconds(1)));
        verify(matching, after(200).never()).liquidateCross(eq(trader), any(), any());

        // 5,000 + 0.1 × (10,000 − 60,000) = 0 is not.
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("10000"), MINUTE_1));

        verify(matching, timeout(WAIT)).liquidateCross(trader, PAIR, MINUTE_1);
        verify(matching, timeout(WAIT)).liquidateCross(eq(trader), eq(other), any());
        verify(matching, timeout(WAIT).atLeastOnce()).crossAccount(trader);
    }

    /** TR-23: at start, and when asked, a liquidation still running is judged again and its next pair force-closed. */
    @Test
    void TR23_aRunningLiquidation_isResumed_atStartAndWhenAsked() {
        UUID trader = UUID.randomUUID();
        when(matching.liquidatingSince(any())).thenReturn(List.of(trader));
        when(matching.liquidateCross(trader, null, MINUTE_2)).thenReturn(Optional.of(PAIR));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).liquidatingSince(MINUTE_2);
        verify(matching, timeout(WAIT)).liquidateCross(trader, PAIR, MINUTE_2);

        worker.resumeLiquidations();

        verify(matching, timeout(WAIT)).liquidatingSince(MINUTE_2.minusSeconds(30));
        verify(matching, timeout(WAIT).times(2)).liquidateCross(trader, PAIR, MINUTE_2);
    }

    /**
     * TR-43: a mark past an isolated position's margin call price, short of its liquidation price, asks for a margin
     * call, once per cooldown; a mark that reaches the liquidation price liquidates instead.
     */
    @Test
    void TR43_aMarkPastTheMarginCallPrice_asksForACall_oncePerCooldown() {
        UUID trader = UUID.randomUUID();
        LiquidationLevel level = new LiquidationLevel(
                UUID.randomUUID(), trader, PAIR, PositionDirection.LONG, new BigDecimal("94"), new BigDecimal("96"));
        when(matching.liquidationLevels()).thenReturn(List.of(level));
        when(matching.marginCall(any(), any(), any(), any())).thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("97"), MINUTE_1));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("95.5"), MINUTE_1.plusSeconds(1)));
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("95"), MINUTE_1.plusSeconds(2)));

        verify(matching, timeout(WAIT))
                .marginCall(trader, level.positionId(), new BigDecimal("95.5"), MINUTE_1.plusSeconds(1));
        verify(matching, after(200).times(1)).marginCall(any(), any(), any(), any());

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("95"), MINUTE_1.plus(Duration.ofMinutes(31))));
        verify(matching, timeout(WAIT).times(2)).marginCall(any(), any(), any(), any());

        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("93"), MINUTE_2.plus(Duration.ofMinutes(40))));
        verify(matching, timeout(WAIT)).liquidate(eq(level), any(), any());
        verify(matching, after(200).times(2)).marginCall(any(), any(), any(), any());
    }

    /** TR-43: a cross snapshot whose ratio reaches 80 %, short of liquidation, asks for a call of the cross positions. */
    @Test
    void TR43_aCrossSnapshotPastTheRatio_asksForACrossCall() {
        UUID trader = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(matching.crossAccounts()).thenReturn(List.of(crossAccount(trader, other)));
        when(matching.marginCall(any(), any(), any(), any())).thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMarkPrice(new MarkPrice(other, new BigDecimal("100"), MINUTE_0));
        // 5,000 + 0.1 × (10,045 − 60,000) = 4.5 against a maintenance of 4.018: 89 %.
        worker.onMarkPrice(new MarkPrice(PAIR, new BigDecimal("10045"), MINUTE_1));

        verify(matching, timeout(WAIT)).marginCall(trader, null, null, MINUTE_1);
        verify(matching, never()).liquidateCross(any(), any(), any());
    }

    private static CrossAccount crossAccount(UUID trader, UUID other) {
        List<LeverageTier> tiers = List.of(new LeverageTier(
                1, BigDecimal.ZERO, new BigDecimal("1000000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO));
        return new CrossAccount(
                trader,
                new BigDecimal("5000"),
                List.of(
                        new CrossAccount.Leg(
                                PAIR, PositionDirection.LONG, new BigDecimal("0.1"), new BigDecimal("60000"), tiers),
                        new CrossAccount.Leg(
                                other, PositionDirection.LONG, BigDecimal.ONE, new BigDecimal("100"), tiers)));
    }

    private PaperMatchingWorker started(PaperMatchingProperties properties) {
        PaperMatchingWorker created = worker(properties);
        created.start();
        return created;
    }

    private PaperMatchingWorker worker(PaperMatchingProperties properties) {
        return new PaperMatchingWorker(matching, market, properties, FUTURES_PROPERTIES, clock, recording);
    }

    private static PaperMatchingProperties properties(boolean enabled, boolean replay, Duration deadline) {
        return properties(enabled, replay, deadline, LimitFillMode.CROSS);
    }

    private static PaperMatchingProperties properties(
            boolean enabled, boolean replay, Duration deadline, LimitFillMode mode) {
        return properties(
                enabled,
                replay,
                deadline,
                mode,
                new PaperMatchingProperties.RecentTrades(Duration.ofSeconds(10), 1000));
    }

    private static PaperMatchingProperties properties(
            boolean enabled,
            boolean replay,
            Duration deadline,
            LimitFillMode mode,
            PaperMatchingProperties.RecentTrades recentTrades) {
        return new PaperMatchingProperties(
                enabled,
                mode,
                Duration.ofSeconds(5),
                Duration.ZERO,
                deadline,
                4,
                Duration.ofSeconds(5),
                new PaperMatchingProperties.Replay(replay, Duration.ofHours(24), Duration.ZERO),
                recentTrades,
                50_000);
    }

    @Test
    void TR02_ordersOfManyTraders_areFilledAtOnce_andThoseOfOneTraderInPriorityOrder() {
        List<RestingOrder> orders = new ArrayList<>();
        for (int trader = 0; trader < 100; trader++) {
            UUID account = UUID.randomUUID();
            // Two orders each: the better price is tracked second, yet must be stored first.
            orders.add(new RestingOrder(
                    UUID.randomUUID(), account, MarketType.SPOT, PAIR, OrderSide.BUY, new BigDecimal("100"), PLACED));
            orders.add(new RestingOrder(
                    UUID.randomUUID(), account, MarketType.SPOT, PAIR, OrderSide.BUY, new BigDecimal("101"), PLACED));
        }
        when(matching.restingOrders()).thenReturn(orders);
        List<RestingOrder> stored = Collections.synchronizedList(new ArrayList<>());
        when(matching.fill(any(), any(), any(), any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return true;
        });
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "90", "110", true));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
        assertThat(stored).hasSize(200).containsExactlyInAnyOrderElementsOf(orders);
        for (int trader = 0; trader < 200; trader += 2) {
            RestingOrder worse = orders.get(trader);
            RestingOrder better = orders.get(trader + 1);
            assertThat(stored.indexOf(better)).isLessThan(stored.indexOf(worse));
        }
    }

    /** The order published to wait inside its placing transaction, as the placement does before it commits. */
    private void published(RestingOrder order) {
        inTransaction(() -> worker.onRestedPublished(new PaperOrderRested(order)));
    }

    private void published(TriggerOrder order) {
        inTransaction(() -> worker.onAwaitingTriggerPublished(new PaperOrderAwaitingTrigger(order)));
    }

    private static void inTransaction(Runnable task) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            task.run();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static RestingOrder buy(String price) {
        return new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal(price),
                PLACED);
    }

    private static RestingOrder sell(String price) {
        return new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.SELL,
                new BigDecimal(price),
                PLACED);
    }

    private static MarketTrade trade(String price, Instant at) {
        return new MarketTrade(MarketType.SPOT, PAIR, 1, new BigDecimal(price), BigDecimal.ONE, at, false, at);
    }

    private static MarketTrade trade(String price, String quantity, boolean buyerMaker, Instant at) {
        return new MarketTrade(
                MarketType.SPOT, PAIR, 1, new BigDecimal(price), new BigDecimal(quantity), at, buyerMaker, at);
    }

    private static MinuteKline kline(Instant open, String low, String high, boolean closed) {
        return new MinuteKline(
                MarketType.SPOT,
                PAIR,
                open,
                new BigDecimal(low),
                new BigDecimal(high),
                new BigDecimal(high),
                closed,
                open.plusSeconds(closed ? 59 : 10));
    }

    private static MinuteKline markKline(Instant open, String first, String high, String low, String close) {
        return new MinuteKline(
                MarketType.FUTURES,
                PAIR,
                open,
                new BigDecimal(low),
                new BigDecimal(high),
                new BigDecimal(close),
                true,
                open.plusSeconds(59),
                new BigDecimal(first));
    }
}
