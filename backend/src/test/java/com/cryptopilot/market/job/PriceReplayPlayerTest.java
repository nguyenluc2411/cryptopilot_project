package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.AggTradeMessage;
import com.cryptopilot.market.client.StreamMessage.BookTickerMessage;
import com.cryptopilot.market.client.StreamMessage.DepthMessage;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.ReplayScenario;
import com.cryptopilot.market.model.ReplayScenario.Point;
import com.cryptopilot.market.model.ReplayStatus;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.impl.LatestMarketData;
import com.cryptopilot.support.MutableTestClock;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * RF-07: what a replay sends through the supervisor at each step, as the exchange's streams would send it, how the
 * minute turns, and how a replay is replaced and stopped.
 */
class PriceReplayPlayerTest {

    private static final Instant AT = Instant.parse("2026-11-27T10:00:30Z");
    private static final UUID BTC = UUID.randomUUID();
    private static final UUID ETH = UUID.randomUUID();

    private final MarketStreamSupervisor supervisor = mock(MarketStreamSupervisor.class);
    private final LatestMarketData latest = new LatestMarketData();
    private final MutableTestClock clock = new MutableTestClock(AT);
    private final List<StreamMessage> sent = new ArrayList<>();
    private final List<Runnable> steps = new ArrayList<>();
    private final List<Duration> periods = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private PriceReplayPlayer player;

    @BeforeEach
    void setUp() {
        when(supervisor.beginReplay(MarketType.FUTURES, "BTCUSDT"))
                .thenReturn(Optional.of(new StreamTarget(BTC, "BTCUSDT")));
        when(supervisor.beginReplay(MarketType.SPOT, "ETHUSDT"))
                .thenReturn(Optional.of(new StreamTarget(ETH, "ETHUSDT")));
        when(supervisor.beginReplay(MarketType.SPOT, "DOGEUSDT")).thenReturn(Optional.empty());
        doAnswer(call -> sent.add(call.getArgument(1))).when(supervisor).replay(any(), any());
        player = new PriceReplayPlayer(supervisor, latest, scheduler(), clock);
    }

    /** Futures: a trade, a book top and a depth around the price, the forming 1-minute candle and the mark price. */
    @Test
    void RF07_aFuturesStep_sendsWhatTheExchangeWould() {
        Optional<ReplayStatus> started = player.start(liq());

        assertThat(started).hasValueSatisfying(status -> {
            assertThat(status.scenario()).isEqualTo("btc_liq");
            assertThat(status.startedAt()).isEqualTo(AT);
            assertThat(status.endsAt()).isEqualTo(AT.plusSeconds(60));
            assertThat(status.price()).isEqualByComparingTo("60000");
        });
        assertThat(periods).containsExactly(Duration.ofSeconds(1));
        assertThat(sent).hasSize(5);
        assertThat(sent.get(0)).isInstanceOfSatisfying(AggTradeMessage.class, trade -> {
            assertThat(trade.price()).isEqualByComparingTo("60000");
            assertThat(trade.aggregateTradeId()).isOne();
        });
        assertThat(sent.get(1)).isInstanceOfSatisfying(BookTickerMessage.class, book -> {
            assertThat(book.bidPrice()).isEqualByComparingTo("59999.95");
            assertThat(book.askPrice()).isEqualByComparingTo("60000.05");
        });
        assertThat(sent.get(2)).isInstanceOfSatisfying(DepthMessage.class, depth -> {
            assertThat(depth.bids()).hasSize(5);
            assertThat(depth.bids().get(1).price()).isEqualByComparingTo("59999.90");
            assertThat(depth.asks().get(1).price()).isEqualByComparingTo("60000.10");
        });
        assertThat(sent.get(3)).isInstanceOfSatisfying(KlineMessage.class, kline -> {
            assertThat(kline.interval()).isEqualTo(MarketInterval.ONE_MINUTE);
            assertThat(kline.closed()).isFalse();
            assertThat(kline.kline().openTime()).isEqualTo(Instant.parse("2026-11-27T10:00:00Z"));
        });
        assertThat(sent.get(4)).isInstanceOfSatisfying(MarkPriceMessage.class, mark -> {
            assertThat(mark.markPrice()).isEqualByComparingTo("60000");
            assertThat(mark.fundingRate()).isZero();
            assertThat(mark.nextFundingTime()).isEqualTo(Instant.parse("2026-11-27T16:00:00Z"));
        });
    }

    /** The mark price carries the funding the exchange last sent; the minute's candle follows the price. */
    @Test
    void RF07_theMarkPrice_keepsTheExchangesFunding_andTheCandleFollowsThePrice() {
        latest.recordMarkPrice(
                BTC,
                new MarkPriceMessage(
                        "BTCUSDT",
                        new BigDecimal("95000"),
                        new BigDecimal("95000"),
                        new BigDecimal("0.0001"),
                        AT.plusSeconds(600),
                        AT));
        player.start(liq());
        sent.clear();

        clock.set(AT.plusSeconds(15));
        steps.getFirst().run();

        MarkPriceMessage mark = last(MarkPriceMessage.class);
        assertThat(mark.markPrice()).isEqualByComparingTo("59750");
        assertThat(mark.fundingRate()).isEqualByComparingTo("0.0001");
        assertThat(mark.nextFundingTime()).isEqualTo(AT.plusSeconds(600));
        KlineMessage kline = last(KlineMessage.class);
        assertThat(kline.kline().open()).isEqualByComparingTo("60000");
        assertThat(kline.kline().high()).isEqualByComparingTo("60000");
        assertThat(kline.kline().low()).isEqualByComparingTo("59750");
        assertThat(kline.kline().close()).isEqualByComparingTo("59750");
        assertThat(kline.kline().tradeCount()).isEqualTo(2);
        assertThat(player.running()).singleElement().satisfies(status -> assertThat(status.price())
                .isEqualByComparingTo("59750"));
    }

    /** When the minute turns, the previous candle is sent closed before the new one opens. */
    @Test
    void RF07_whenTheMinuteTurns_thePreviousCandleIsSentClosedFirst() {
        player.start(liq());
        sent.clear();

        clock.set(Instant.parse("2026-11-27T10:01:00Z"));
        steps.getFirst().run();

        List<KlineMessage> klines = sent.stream()
                .filter(KlineMessage.class::isInstance)
                .map(KlineMessage.class::cast)
                .toList();
        assertThat(klines).hasSize(2);
        assertThat(klines.get(0).closed()).isTrue();
        assertThat(klines.get(0).kline().openTime()).isEqualTo(Instant.parse("2026-11-27T10:00:00Z"));
        assertThat(klines.get(0).kline().closeTime()).isEqualTo(Instant.parse("2026-11-27T10:00:59.999Z"));
        assertThat(klines.get(1).closed()).isFalse();
        assertThat(klines.get(1).kline().openTime()).isEqualTo(Instant.parse("2026-11-27T10:01:00Z"));
        assertThat(klines.get(1).kline().open())
                .isEqualByComparingTo(klines.get(1).kline().close());
        assertThat(sent.indexOf(klines.get(0))).isZero();
    }

    /** Spot: the ticker instead of the mark price, the exchange's 24-hour figures widened to the price. */
    @Test
    void RF07_aSpotStep_sendsTheTicker_withTheExchanges24HourFiguresWidened() {
        latest.recordTicker(
                ETH,
                new TickerMessage(
                        "ETHUSDT",
                        new BigDecimal("3500"),
                        new BigDecimal("3499.99"),
                        new BigDecimal("3500.01"),
                        new BigDecimal("3600"),
                        new BigDecimal("3400"),
                        new BigDecimal("1.5"),
                        new BigDecimal("1000"),
                        new BigDecimal("3500000"),
                        AT));

        player.start(alert());

        assertThat(sent).noneMatch(MarkPriceMessage.class::isInstance);
        TickerMessage ticker = last(TickerMessage.class);
        assertThat(ticker.lastPrice()).isEqualByComparingTo("3010");
        assertThat(ticker.highPrice24h()).isEqualByComparingTo("3600");
        assertThat(ticker.lowPrice24h()).isEqualByComparingTo("3010");
        assertThat(ticker.priceChangePercent24h()).isEqualByComparingTo("1.5");
        assertThat(ticker.quoteVolume24h()).isEqualByComparingTo("3500000");
    }

    /** With nothing from the exchange yet, the ticker's 24-hour figures are the price and zero. */
    @Test
    void RF07_aSpotStep_withoutAnExchangeTicker_usesThePrice() {
        player.start(alert());

        TickerMessage ticker = last(TickerMessage.class);
        assertThat(ticker.highPrice24h()).isEqualByComparingTo("3010");
        assertThat(ticker.lowPrice24h()).isEqualByComparingTo("3010");
        assertThat(ticker.baseVolume24h()).isZero();
    }

    /** A pair its market does not stream is not replayed: nothing is scheduled or sent. */
    @Test
    void RF07_aPairNotStreamed_isNotReplayed() {
        ReplayScenario doge = new ReplayScenario(
                "doge", "", MarketType.SPOT, "DOGEUSDT", 1000, BigDecimal.ONE, List.of(new Point(0, BigDecimal.ONE)));

        assertThat(player.start(doge)).isEmpty();
        assertThat(steps).isEmpty();
        assertThat(sent).isEmpty();
        assertThat(player.running()).isEmpty();
    }

    /** A second scenario on the pair replaces the first and keeps the minute's candle; stopping ends the replay. */
    @Test
    void RF07_aSecondScenario_replacesTheFirst_andStoppingEndsTheReplay() {
        player.start(hold());
        clock.set(AT.plusSeconds(10));
        player.start(liq());

        assertThat(futures.getFirst().isCancelled()).isTrue();
        assertThat(player.running()).singleElement().satisfies(status -> assertThat(status.scenario())
                .isEqualTo("btc_liq"));
        assertThat(last(KlineMessage.class).kline().tradeCount()).isEqualTo(2);

        player.start(alert());
        assertThat(player.running())
                .extracting(ReplayStatus::market)
                .containsExactly(MarketType.SPOT, MarketType.FUTURES);

        assertThat(player.stop(MarketType.FUTURES, "BTCUSDT")).isTrue();
        assertThat(player.stop(MarketType.FUTURES, "BTCUSDT")).isFalse();
        assertThat(futures.get(1).isCancelled()).isTrue();
        verify(supervisor).endReplay(MarketType.FUTURES, "BTCUSDT");
        verify(supervisor, never()).endReplay(eq(MarketType.SPOT), any());

        player.stopAll();
        assertThat(player.running()).isEmpty();
        verify(supervisor).endReplay(MarketType.SPOT, "ETHUSDT");
    }

    /** A step that fails is logged and the schedule goes on. */
    @Test
    void RF07_aFailedStep_doesNotStopTheReplay() {
        player.start(liq());
        doAnswer(call -> {
                    throw new IllegalStateException("engine down");
                })
                .when(supervisor)
                .replay(any(), any());

        steps.getFirst().run();

        assertThat(player.running()).hasSize(1);
    }

    private <T extends StreamMessage> T last(Class<T> type) {
        return sent.reversed().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    private static ReplayScenario liq() {
        return new ReplayScenario(
                "btc_liq",
                "",
                MarketType.FUTURES,
                "BTCUSDT",
                1000,
                new BigDecimal("0.1"),
                List.of(new Point(0, new BigDecimal("60000.0")), new Point(60, new BigDecimal("59000.0"))));
    }

    private static ReplayScenario hold() {
        return new ReplayScenario(
                "btc_hold_60000",
                "",
                MarketType.FUTURES,
                "BTCUSDT",
                1000,
                new BigDecimal("0.1"),
                List.of(new Point(0, new BigDecimal("60000.0"))));
    }

    private static ReplayScenario alert() {
        return new ReplayScenario(
                "eth_alert",
                "",
                MarketType.SPOT,
                "ETHUSDT",
                1000,
                new BigDecimal("0.01"),
                List.of(new Point(0, new BigDecimal("3010.00")), new Point(60, new BigDecimal("2940.00"))));
    }

    private TaskScheduler scheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(), new Class<?>[] {TaskScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduleAtFixedRate") && args[1] instanceof Duration period) {
                        steps.add((Runnable) args[0]);
                        periods.add(period);
                        ScheduledFuture<?> future = new CancellableFuture();
                        futures.add(future);
                        return future;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A schedule that only remembers being cancelled. */
    private static final class CancellableFuture implements ScheduledFuture<Object> {

        private boolean cancelled;

        @Override
        public long getDelay(java.util.concurrent.TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, java.util.concurrent.TimeUnit unit) {
            return null;
        }
    }
}
