package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.config.ReplayLockProperties;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.service.MatchingService;
import com.cryptopilot.trading.service.impl.ReplayLockServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * D-79 with a real Redis lease: a replay that failed waits for its retry longer than the key's TTL, and the key stays
 * alive the whole time, renewed by the heartbeat; it is deleted only when the retry succeeds.
 *
 * <p>Rule: NSF-07; D-79, D-85.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ReplayLockBackoffTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000d001");
    private static final Instant NOW = Instant.parse("2026-10-03T08:10:30Z");
    private static final Instant M0 = Instant.parse("2026-10-03T08:00:00Z");
    private static final String KEY = "rpl:SPOT:" + PAIR;

    /** The key lives 300 ms without renewal; the retry comes after a full second. */
    private static final ReplayLockProperties LEASE =
            new ReplayLockProperties(Duration.ofMillis(300), Duration.ofMillis(100));

    private static final Duration BACKOFF = Duration.ofSeconds(1);

    @Autowired
    private StringRedisTemplate redis;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private MinuteKlineFeed feed;

    @AfterEach
    void stop() {
        if (feed != null) {
            feed.stop();
        }
        scheduler.shutdown();
        redis.delete(KEY);
    }

    @Test
    void D79_aBackoffLongerThanTheTtl_keepsTheKeyAlive_untilTheRetrySucceeds() throws Exception {
        scheduler.initialize();
        ReplayLockServiceImpl locks = new ReplayLockServiceImpl(redis, LEASE, scheduler);
        locks.startHeartbeat();

        MatchingWorker worker = mock(MatchingWorker.class);
        when(worker.isRunning()).thenReturn(true);
        List<PriceRange> submitted = new CopyOnWriteArrayList<>();
        doAnswer(call -> submitted.add(call.getArgument(0))).when(worker).submit(any());
        MatchingService matching = mock(MatchingService.class);
        when(matching.activeEntries()).thenReturn(List.of());
        when(matching.watermark(any(), any())).thenReturn(Optional.empty());
        MarketApi market = mock(MarketApi.class);
        CountDownLatch firstAttemptFailed = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        // Nothing is served after the gap; the stream carries on from there.
        when(market.closedMinuteKlines(any(), any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2))).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                firstAttemptFailed.countDown();
                throw new IllegalStateException("exchange unavailable");
            }
            return MinuteKlineBatch.of(List.of(closed(minute(2)), closed(minute(3))));
        });

        feed = new MinuteKlineFeed(
                worker,
                matching,
                market,
                new MatchingProperties(
                        true,
                        1,
                        100,
                        new MatchingProperties.Retry(BACKOFF, BACKOFF, 0, Duration.ofMinutes(10)),
                        60,
                        Duration.ofSeconds(5),
                        new MatchingProperties.Replay(true, Duration.ofHours(24), Duration.ZERO, 0, 1440)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                locks);
        feed.start();
        feed.onMinuteKline(update(minute(1), true));
        feed.onMinuteKline(update(minute(4), false));

        assertThat(firstAttemptFailed.await(5, TimeUnit.SECONDS)).isTrue();
        long until = System.nanoTime() + BACKOFF.minusMillis(150).toNanos();
        while (System.nanoTime() < until && attempts.get() == 1) {
            assertThat(redis.hasKey(KEY))
                    .as("the key outlives its TTL while the retry waits")
                    .isTrue();
            assertThat(feed.isReplaying(MarketType.SPOT, PAIR)).isTrue();
            Thread.sleep(25);
        }
        assertThat(attempts.get())
                .as("still waiting for the retry when sampling ended")
                .isEqualTo(1);

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(KEY)) || submitted.size() < 4) {
            assertThat(System.nanoTime())
                    .as("the retry succeeds and frees the key")
                    .isLessThan(deadline);
            Thread.sleep(25);
        }
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(feed.isReplaying(MarketType.SPOT, PAIR)).isFalse();
    }

    private static Instant minute(long n) {
        return M0.plus(Duration.ofMinutes(n));
    }

    private static MinuteKline update(Instant open, boolean closed) {
        return new MinuteKline(
                MarketType.SPOT,
                PAIR,
                open,
                new BigDecimal("100"),
                new BigDecimal("101"),
                closed,
                open.plusSeconds(closed ? 60 : 5).minusMillis(closed ? 1 : 0));
    }

    private static MinuteKline closed(Instant open) {
        return update(open, true);
    }
}
