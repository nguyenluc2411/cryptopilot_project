package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperMatchingProperties;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.LimitFillMode;
import com.cryptopilot.paper.model.enums.OrderSide;
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
        when(matching.watermark(any(), any())).thenReturn(Optional.empty());
        when(matching.fill(any(), any(), any())).thenReturn(true);
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

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
    }

    @Test
    void TR02_formingUpdatesOfACandle_areMerged_andFillWhatTheyReached_withoutMovingTheWatermark() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "101", "102", false));
        worker.onMinuteKline(kline(MINUTE_1, "99.5", "102", false));

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE);
        verify(matching, after(200).never()).advanceWatermark(any(), any(), any());
    }

    @Test
    void D77_aCandleThatOpenedBeforeTheOrderWasPlaced_doesNotFillIt() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_0, "90", "110", true));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_0);
        verify(matching, never()).fill(any(), any(), any());
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

        verify(matching, timeout(WAIT)).fill(kept, MINUTE_1, FillSource.LIVE);
        verify(matching, never()).fill(eq(cancelled), any(), any());
    }

    @Test
    void TR02_aFillThatFails_isTriedAgainOnThePairsNextRange_andHoldsTheWatermarkMeanwhile() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE);
        worker.onMinuteKline(kline(MINUTE_2, "120", "130", false));

        verify(matching, timeout(WAIT).times(2)).fill(order, MINUTE_1, FillSource.LIVE);
        verify(matching, never()).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
    }

    @Test
    void TR02_aCancelWhileAFillWaitsForItsRetry_dropsTheRetry() {
        RestingOrder order = buy("100");
        RestingOrder keeper = buy("10");
        when(matching.restingOrders()).thenReturn(List.of(order, keeper));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE)).thenThrow(new IllegalStateException("database down"));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE);
        worker.onCanceled(new PaperOrderCanceled(order.orderId()));
        worker.onMinuteKline(kline(MINUTE_2, "90", "130", true));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_2);
        verify(matching, never()).fill(order, MINUTE_2, FillSource.LIVE);
        verify(matching).fill(order, MINUTE_1, FillSource.LIVE);
    }

    @Test
    void TR02_aFillFailingPastItsDeadline_isGivenUp_theOrderWaitsAgain_andTheWatermarkStaysHeld() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE)).thenThrow(new IllegalStateException("bug"));
        worker = started(properties(true, false, Duration.ZERO));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE);
        worker.onMinuteKline(kline(MINUTE_2, "99", "110", true));

        verify(matching, timeout(WAIT)).fill(order, MINUTE_2, FillSource.LIVE);
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

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.REPLAY);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
        verify(market, timeout(WAIT)).closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_2);
        verify(matching, never()).fill(order, MINUTE_0, FillSource.REPLAY);
    }

    @Test
    void Q52_aReplay_prefetchesTheQuoteRatesOfItsCandles_beforeTheirFills() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, MINUTE_0))
                .thenReturn(MinuteKlineBatch.of(
                        List.of(kline(MINUTE_0, "101", "110", true), kline(MINUTE_1, "99", "110", true))));
        worker = started(properties(true, true, Duration.ofMinutes(10)));

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.REPLAY);
        InOrder sequence = inOrder(matching);
        sequence.verify(matching).prefetchQuoteRates(MarketType.SPOT, PAIR, MINUTE_0, MINUTE_1);
        sequence.verify(matching).fill(order, MINUTE_1, FillSource.REPLAY);
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

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.REPLAY);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
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

        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.REPLAY);
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
        when(matching.fill(first, MINUTE_1, FillSource.LIVE)).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("database down"))
                .when(matching)
                .advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onMinuteKline(kline(MINUTE_1, "99", "110", true));
        worker.onMinuteKline(kline(MINUTE_2, "89", "110", true));

        verify(matching, timeout(WAIT)).fill(second, MINUTE_2, FillSource.LIVE);
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

        verify(matching, timeout(WAIT)).fill(buy, low, FillSource.LIVE);
        verify(matching, timeout(WAIT)).fill(sell, high, FillSource.LIVE);
        verify(matching, after(200).never()).fill(eq(away), any(), any());
        verify(matching, never()).advanceWatermark(any(), any(), any());
    }

    @Test
    void QT8_aTradeAtTheOrdersOwnPrice_fillsItOnlyWithTouch() {
        RestingOrder crossOrder = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(crossOrder));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("100", MINUTE_1));

        verify(matching, after(200).never()).fill(any(), any(), any());
        worker.stop();

        RestingOrder touchOrder = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(touchOrder));
        worker = started(properties(true, false, Duration.ofMinutes(10), LimitFillMode.TOUCH));

        worker.onTrade(trade("100", MINUTE_1));

        verify(matching, timeout(WAIT)).fill(touchOrder, MINUTE_1, FillSource.LIVE);
    }

    @Test
    void D77_aTradeBeforeTheOrderWasPlaced_doesNotFillIt_butTheNextOneDoes() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("99", PLACED.minusMillis(1)));
        verify(matching, after(200).never()).fill(any(), any(), any());
        worker.onTrade(trade("99", PLACED.plusMillis(1)));

        verify(matching, timeout(WAIT)).fill(order, PLACED.plusMillis(1), FillSource.LIVE);
    }

    @Test
    void TR05_whileTradesFlow_formingCandlesFillNothing_aClosedOneStillDoes_andSilenceGivesThemBack() {
        RestingOrder byClosed = buy("100");
        RestingOrder bySilence = buy("95");
        when(matching.restingOrders()).thenReturn(List.of(byClosed, bySilence));
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("120", MINUTE_1));
        worker.onMinuteKline(kline(MINUTE_1, "99", "120", false));
        verify(matching, after(200).never()).fill(any(), any(), any());

        worker.onMinuteKline(kline(MINUTE_1, "99", "120", true));
        verify(matching, timeout(WAIT)).fill(byClosed, MINUTE_1, FillSource.LIVE);
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, MINUTE_1);

        clock.advance(Duration.ofSeconds(5));
        worker.onMinuteKline(kline(MINUTE_2, "94", "120", false));
        verify(matching, timeout(WAIT)).fill(bySilence, MINUTE_2, FillSource.LIVE);
    }

    @Test
    void TR05_aFillATradeDecidedThatFails_isTriedAgainOnThePairsNextTrade() {
        RestingOrder order = buy("100");
        when(matching.restingOrders()).thenReturn(List.of(order));
        when(matching.fill(order, MINUTE_1, FillSource.LIVE))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.onTrade(trade("99", MINUTE_1));
        verify(matching, timeout(WAIT)).fill(order, MINUTE_1, FillSource.LIVE);
        worker.onTrade(trade("120", MINUTE_1.plusSeconds(1)));

        verify(matching, timeout(WAIT).times(2)).fill(order, MINUTE_1, FillSource.LIVE);
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
        verify(matching, after(200).never()).fill(any(), any(), any());
        worker.onTrade(trade("80000", "1", true, MINUTE_1.plusSeconds(1)));
        verify(matching, timeout(WAIT)).fill(atLevel, MINUTE_1.plusSeconds(1), FillSource.LIVE);

        worker.onTrade(trade("79995", "0.001", true, MINUTE_1.plusSeconds(2)));
        verify(matching, timeout(WAIT)).fill(between, MINUTE_1.plusSeconds(2), FillSource.LIVE);
    }

    @Test
    void TR05_beforeStartOrWhenDisabled_aTradeIsIgnored() {
        worker = worker(properties(false, false, Duration.ofMinutes(10)));
        worker.start();

        worker.onTrade(trade("1", MINUTE_1));

        assertThat(worker.isIdle()).isTrue();
        verifyNoInteractions(matching);
    }

    @Test
    void TR02_aSecondStart_doesNothing() {
        worker = started(properties(true, false, Duration.ofMinutes(10)));

        worker.start();

        assertThat(started).hasSize(2);
    }

    private PaperMatchingWorker started(PaperMatchingProperties properties) {
        PaperMatchingWorker created = worker(properties);
        created.start();
        return created;
    }

    private PaperMatchingWorker worker(PaperMatchingProperties properties) {
        return new PaperMatchingWorker(matching, market, properties, clock, recording);
    }

    private static PaperMatchingProperties properties(boolean enabled, boolean replay, Duration deadline) {
        return properties(enabled, replay, deadline, LimitFillMode.CROSS);
    }

    private static PaperMatchingProperties properties(
            boolean enabled, boolean replay, Duration deadline, LimitFillMode mode) {
        return new PaperMatchingProperties(
                enabled,
                mode,
                Duration.ofSeconds(5),
                Duration.ZERO,
                deadline,
                4,
                Duration.ofSeconds(5),
                new PaperMatchingProperties.Replay(replay, Duration.ofHours(24), Duration.ZERO));
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
        when(matching.fill(any(), any(), any())).thenAnswer(call -> {
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
}
