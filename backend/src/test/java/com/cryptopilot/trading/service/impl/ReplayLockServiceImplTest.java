package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.config.ReplayLockProperties;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;

/**
 * The replay lease against a real Redis (D-85): one key per pair, taken only if absent, renewed by its holder's
 * heartbeat, deleted by its holder only, and left to expire when the holder is gone.
 *
 * <p>Two service instances stand for two application instances, or for the process before and after a crash: each has
 * its own token.
 *
 * <p>Rule: NSF-07; D-79, D-85.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ReplayLockServiceImplTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c001");

    private static final ReplayLockProperties PROPERTIES =
            new ReplayLockProperties(Duration.ofSeconds(90), Duration.ofSeconds(30));

    @Autowired
    private StringRedisTemplate redis;

    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @AfterEach
    void removeKeys() {
        Set<String> keys = redis.keys(ReplayLockServiceImpl.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void D85_aStart_setsOneKeyPerPair_withTheTtl() {
        assertThat(service(PROPERTIES).tryAcquire(MarketType.SPOT, PAIR)).isTrue();

        String key = "rpl:SPOT:" + PAIR;
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key)).isBetween(80L, 90L);
    }

    @Test
    void D85_aSecondStart_isRejected_whileTheFirstHoldsTheKey() {
        ReplayLockServiceImpl first = service(PROPERTIES);
        ReplayLockServiceImpl second = service(PROPERTIES);

        assertThat(first.tryAcquire(MarketType.SPOT, PAIR)).isTrue();
        assertThat(second.tryAcquire(MarketType.SPOT, PAIR)).isFalse();
        assertThat(first.tryAcquire(MarketType.SPOT, PAIR))
                .as("the holder itself")
                .isTrue();
        assertThat(second.tryAcquire(MarketType.FUTURES, PAIR))
                .as("another market is another key")
                .isTrue();
    }

    /** Finish and failure both end in a release; only the holder's release deletes the key. */
    @Test
    void D85_releasingTheKey_letsAnotherStart_andAStrangersReleaseDoesNothing() {
        ReplayLockServiceImpl first = service(PROPERTIES);
        ReplayLockServiceImpl second = service(PROPERTIES);
        first.tryAcquire(MarketType.SPOT, PAIR);

        second.release(MarketType.SPOT, PAIR);
        assertThat(first.isReplaying(MarketType.SPOT, PAIR))
                .as("not the stranger's to release")
                .isTrue();

        first.release(MarketType.SPOT, PAIR);
        assertThat(first.isReplaying(MarketType.SPOT, PAIR)).isFalse();
        assertThat(second.tryAcquire(MarketType.SPOT, PAIR)).isTrue();
    }

    @Test
    void D85_releaseAll_freesEveryKeyTheInstanceHolds() {
        ReplayLockServiceImpl holder = service(PROPERTIES);
        UUID other = UUID.fromString("019b76da-a800-7000-8000-00000000c002");
        holder.tryAcquire(MarketType.SPOT, PAIR);
        holder.tryAcquire(MarketType.FUTURES, other);

        holder.releaseAll();

        assertThat(redis.keys(ReplayLockServiceImpl.KEY_PREFIX + "*")).isEmpty();
    }

    /** A holder that crashed renews nothing: once its key expires, a new start succeeds. */
    @Test
    void D85_anExpiredKey_allowsARestart() throws InterruptedException {
        ReplayLockProperties shortLived = new ReplayLockProperties(Duration.ofMillis(300), Duration.ofMillis(100));
        service(shortLived).tryAcquire(MarketType.SPOT, PAIR);
        ReplayLockServiceImpl restarted = service(shortLived);
        assertThat(restarted.tryAcquire(MarketType.SPOT, PAIR)).isFalse();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey("rpl:SPOT:" + PAIR))) {
            assertThat(System.nanoTime()).as("the key expires in time").isLessThan(deadline);
            Thread.sleep(20);
        }

        assertThat(restarted.tryAcquire(MarketType.SPOT, PAIR)).isTrue();
    }

    @Test
    void D85_theHeartbeat_renewsTheTtlOfTheKeysHeld() {
        ReplayLockServiceImpl holder = service(PROPERTIES);
        holder.tryAcquire(MarketType.SPOT, PAIR);
        String key = "rpl:SPOT:" + PAIR;
        redis.expire(key, Duration.ofSeconds(5));

        assertThat(holder.heartbeat()).isOne();

        assertThat(redis.getExpire(key)).isBetween(80L, 90L);
    }

    /** A key that expired and was taken by another instance is no longer this holder's: it is not renewed. */
    @Test
    void D85_theHeartbeat_dropsAKeyItNoLongerOwns() {
        ReplayLockServiceImpl holder = service(PROPERTIES);
        holder.tryAcquire(MarketType.SPOT, PAIR);
        redis.delete("rpl:SPOT:" + PAIR);
        service(PROPERTIES).tryAcquire(MarketType.SPOT, PAIR);

        assertThat(holder.heartbeat()).isZero();
        assertThat(holder.heartbeat()).as("dropped, not retried").isZero();
    }

    @Test
    void D85_theHeartbeat_isScheduledAtItsInterval() {
        service(PROPERTIES).startHeartbeat();

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(30)));
    }

    @Test
    void D85_aTtlNotAboveTwoHeartbeats_isRefused() {
        assertThatThrownBy(() -> new ReplayLockProperties(Duration.ofSeconds(60), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplayLockProperties(Duration.ofSeconds(60), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReplayLockServiceImpl service(ReplayLockProperties properties) {
        return new ReplayLockServiceImpl(redis, properties, scheduler);
    }
}
