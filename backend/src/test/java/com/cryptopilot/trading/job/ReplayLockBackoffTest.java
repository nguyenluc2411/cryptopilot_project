package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>The retry waits on the real clock inside the feed, so the test does not sample the key while it waits: it looks
 * at the key at the moment the retry asks for the lock again. Only that call could re-create an expired key, so a key
 * found there, with a TTL no longer than the lease, has been renewed without a gap. The end is awaited with a bound
 * rather than a sleep loop.
 *
 * <p>Rule: NSF-07; D-79, D-85.
 *
 * <p>Reference: Freeman, S., &amp; Pryce, N. (2009). <i>Growing Object-Oriented Software, Guided by Tests</i>.
 * Addison-Wesley, ch. 27 (testing asynchronous code); Luo, Q., Hariri, F., Eloussi, L., &amp; Marinov, D. (2014). An
 * Empirical Analysis of Flaky Tests. FSE 2014.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ReplayLockBackoffTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000d001");
    private static final Instant NOW = Instant.parse("2026-10-03T08:10:30Z");
    private static final Instant M0 = Instant.parse("2026-10-03T08:00:00Z");
    private static final String KEY = "rpl:SPOT:" + PAIR;

    /** The key lives 500 ms without renewal; the retry comes after three times that. */
    private static final ReplayLockProperties LEASE =
            new ReplayLockProperties(Duration.ofMillis(500), Duration.ofMillis(100));

    private static final Duration BACKOFF = Duration.ofMillis(1500);

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
        List<LockAsked> asked = new CopyOnWriteArrayList<>();
        ReplayLockServiceImpl locks = new ReplayLockServiceImpl(redis, LEASE, scheduler) {
            @Override
            public boolean tryAcquire(MarketType market, UUID pairId) {
                asked.add(new LockAsked(
                        System.nanoTime(),
                        Boolean.TRUE.equals(redis.hasKey(KEY)),
                        redis.getExpire(KEY, TimeUnit.MILLISECONDS)));
                return super.tryAcquire(market, pairId);
            }
        };
        locks.startHeartbeat();

        MatchingWorker worker = mock(MatchingWorker.class);
        when(worker.isRunning()).thenReturn(true);
        List<PriceRange> submitted = new CopyOnWriteArrayList<>();
        doAnswer(call -> submitted.add(call.getArgument(0))).when(worker).submit(any());
        MatchingService matching = mock(MatchingService.class);
        when(matching.activeEntries()).thenReturn(List.of());
        when(matching.watermark(any(), any())).thenReturn(Optional.empty());
        MarketApi market = mock(MarketApi.class);
        AtomicLong failedAt = new AtomicLong();
        AtomicInteger attempts = new AtomicInteger();
        // Nothing is served after the gap; the stream carries on from there.
        when(market.closedMinuteKlines(any(), any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
        when(market.closedMinuteKlines(MarketType.SPOT, PAIR, minute(2))).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                failedAt.set(System.nanoTime());
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

        await("the retry succeeds and frees the key")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> submitted.size() >= 4 && !Boolean.TRUE.equals(redis.hasKey(KEY)));

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(asked).as("the first attempt and its retry ask for the lock").hasSize(2);
        assertThat(asked.getFirst().present())
                .as("no key before the first attempt")
                .isFalse();
        LockAsked retry = asked.get(1);
        assertThat(Duration.ofNanos(retry.atNanos() - failedAt.get()))
                .as("the retry waited longer than the TTL")
                .isGreaterThanOrEqualTo(BACKOFF)
                .isGreaterThan(LEASE.ttl());
        assertThat(retry.present())
                .as("the key outlived its TTL while the retry waited")
                .isTrue();
        assertThat(retry.ttlMillis())
                .as("still a lease, renewed")
                .isBetween(1L, LEASE.ttl().toMillis());
        assertThat(feed.isReplaying(MarketType.SPOT, PAIR)).isFalse();
    }

    /** What the key looked like when the feed asked for the lock. */
    private record LockAsked(long atNanos, boolean present, Long ttlMillis) {}

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
                new BigDecimal("101"),
                closed,
                open.plusSeconds(closed ? 60 : 5).minusMillis(closed ? 1 : 0));
    }

    private static MinuteKline closed(Instant open) {
        return update(open, true);
    }
}
