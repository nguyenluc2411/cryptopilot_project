package com.cryptopilot.market.job;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static com.cryptopilot.market.client.StubStreamServer.signal;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.market.service.impl.StreamCandleServiceImpl;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The partitioned hand-over from the stream readers to storage: the candles of one pair stored in order, a
 * reader never waiting, and a failure never stopping a consumer.
 *
 * <p>The two ways a consumer ends are each driven by one test: the threads come from a factory the test holds,
 * so it can see a consumer waiting, stop it and wait for it to end, instead of hoping the interrupt lands in one
 * place or the other.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 7.1 step 2 and 10.
 *
 * <p>Reference: Meszaros, G. (2007). <i>xUnit Test Patterns</i>. Addison-Wesley, "Erratic Test" (a result that
 * depends on thread timing) and "Test Double" (a stand-in the test controls).
 */
class ClosedCandlePipelineTest {

    private static final Instant OPEN = Instant.parse("2026-09-24T00:00:00Z");

    private final List<String> stored = new CopyOnWriteArrayList<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final ThreadFactory recordingThreads = runnable -> {
        Thread thread = Thread.ofVirtual().unstarted(runnable);
        threads.add(thread);
        return thread;
    };
    private ClosedCandlePipeline pipeline;

    @AfterEach
    void stop() {
        if (pipeline != null) {
            pipeline.stop();
        }
    }

    /** One pair's candles are stored in the order they arrived; other pairs go their own way. */
    @Test
    void NSF03_aPairsCandles_areStoredInOrder() {
        pipeline = new ClosedCandlePipeline(recording(null, null), properties(4, 100));
        pipeline.start();
        pipeline.start();
        StreamTarget btc = new StreamTarget(UUID.randomUUID(), "BTCUSDT");
        StreamTarget eth = new StreamTarget(UUID.randomUUID(), "ETHUSDT");

        for (int hour = 0; hour < 20; hour++) {
            assertThat(pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", hour)))
                    .isTrue();
            assertThat(pipeline.submit(MarketType.FUTURES, eth, closed("ETHUSDT", hour)))
                    .isTrue();
        }

        await(() -> stored.size() == 40, "forty candles");
        assertThat(stored.stream().filter(s -> s.startsWith("BTCUSDT")))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20)
                        .mapToObj(hour -> "BTCUSDT " + OPEN.plus(Duration.ofHours(hour)))
                        .toList());
    }

    /** A full partition refuses at once rather than holding the reader. */
    @Test
    void NSF03_aFullPartition_refusesWithoutWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(1);
        pipeline = new ClosedCandlePipeline(recording(busy, release), properties(1, 1));
        pipeline.start();
        StreamTarget btc = new StreamTarget(UUID.randomUUID(), "BTCUSDT");

        pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", 0));
        busy.await();
        assertThat(pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", 1))).isTrue();
        // The consumer is held and the queue is full; a submit that waited would not return before the release.
        CompletableFuture<Boolean> third =
                CompletableFuture.supplyAsync(() -> pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", 2)));
        await(third::isDone, "the refused submit to return while the consumer is still held");
        assertThat(third.join()).isFalse();
        assertThat(release.getCount())
                .as("returned before anything was released")
                .isOne();

        release.countDown();
        await(() -> stored.size() == 2, "the two queued candles");
    }

    /** A candle that cannot be stored is logged; the consumer carries on with the next. */
    @Test
    void NSF03_aFailedCandle_doesNotStopTheConsumer() {
        pipeline = new ClosedCandlePipeline(recording(null, null), properties(1, 10));
        pipeline.start();
        StreamTarget broken = new StreamTarget(UUID.randomUUID(), "FAILUSDT");
        StreamTarget btc = new StreamTarget(UUID.randomUUID(), "BTCUSDT");

        pipeline.submit(MarketType.SPOT, broken, closed("FAILUSDT", 0));
        pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", 0));

        await(() -> stored.size() == 1, "the healthy candle");
        assertThat(stored).containsExactly("BTCUSDT " + OPEN);
    }

    /** Stopped, the consumers end; a stopped pipeline can be started again. */
    @Test
    void NSF03_aStoppedPipeline_canStartAgain() {
        pipeline = new ClosedCandlePipeline(recording(null, null), properties(2, 10));
        pipeline.start();
        pipeline.stop();
        pipeline.start();

        pipeline.submit(MarketType.SPOT, new StreamTarget(UUID.randomUUID(), "BTCUSDT"), closed("BTCUSDT", 0));

        await(() -> stored.size() == 1, "the candle");
    }

    /** A consumer waiting for work ends when stopped. */
    @Test
    void NSF03_stoppingAWaitingConsumer_endsIt() throws Exception {
        pipeline = new ClosedCandlePipeline(recording(null, null), properties(2, 10), recordingThreads);
        pipeline.start();
        assertThat(threads)
                .hasSize(2)
                .extracting(Thread::getName)
                .containsExactly("market-candles-0", "market-candles-1");
        for (Thread consumer : threads) {
            await(() -> consumer.getState() == Thread.State.WAITING, "the consumer to wait for work");
        }

        pipeline.stop();

        for (Thread consumer : threads) {
            assertThat(consumer.join(Duration.ofSeconds(5))).as("ended").isTrue();
        }
    }

    /** A consumer stopped while it stores a candle finishes that candle, then ends without taking another. */
    @Test
    void NSF03_stoppingAConsumerWhileItStores_endsItAfterTheCandle() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StreamCandleService holding =
                new StreamCandleServiceImpl(null, null, null, null, event -> {}, Clock.systemUTC()) {
                    @Override
                    public boolean onKline(MarketType market, StreamTarget target, KlineMessage message) {
                        busy.countDown();
                        // Waits through the interrupt, as a database call would, and keeps it for the consumer's loop.
                        boolean interrupted = false;
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        stored.add(target.symbol());
                        return true;
                    }
                };
        pipeline = new ClosedCandlePipeline(holding, properties(1, 10), recordingThreads);
        pipeline.start();
        StreamTarget btc = new StreamTarget(UUID.randomUUID(), "BTCUSDT");
        pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", 0));
        pipeline.submit(MarketType.SPOT, btc, closed("BTCUSDT", 1));
        busy.await();

        pipeline.stop();
        release.countDown();

        assertThat(threads.getFirst().join(Duration.ofSeconds(5))).as("ended").isTrue();
        assertThat(stored).containsExactly("BTCUSDT");
    }

    /** A stand-in for the storage service that records, fails for one symbol, and can be held. */
    private StreamCandleService recording(CountDownLatch busy, CountDownLatch release) {
        return new StreamCandleServiceImpl(null, null, null, null, event -> {}, Clock.systemUTC()) {
            @Override
            public boolean onKline(MarketType market, StreamTarget target, KlineMessage message) {
                if (target.symbol().equals("FAILUSDT")) {
                    throw new IllegalStateException("the database is down");
                }
                if (busy != null && stored.isEmpty() && busy.getCount() > 0) {
                    busy.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                stored.add(target.symbol() + " " + message.kline().openTime());
                signal();
                return true;
            }
        };
    }

    private static KlineMessage closed(String symbol, int hour) {
        Instant open = OPEN.plus(Duration.ofHours(hour));
        Kline kline = new Kline(
                open,
                open.plus(Duration.ofHours(1)).minusMillis(1),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                1);
        return new KlineMessage(symbol, MarketInterval.ONE_HOUR, kline, true, open.plus(Duration.ofHours(1)));
    }

    private static BinanceStreamProperties properties(int partitions, int capacity) {
        return new BinanceStreamProperties(
                true,
                URI.create("ws://127.0.0.1:1"),
                URI.create("ws://127.0.0.1:1/market"),
                100,
                Duration.ofSeconds(2),
                new BinanceStreamProperties.Reconnect(Duration.ofSeconds(1), Duration.ofSeconds(60), 20),
                Duration.ofHours(23),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                partitions,
                capacity);
    }
}
