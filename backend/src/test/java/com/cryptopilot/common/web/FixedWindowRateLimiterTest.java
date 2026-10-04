package com.cryptopilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The fixed-window counter of ADR-014 against a real Redis: requests up to the limit pass, the next is refused with
 * the seconds to the end of the window, a new window starts from zero, and two instances share one count.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3; ADR-014.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FixedWindowRateLimiterTest {

    /** 20 seconds into a minute. */
    private static final Instant AT = Instant.parse("2026-10-04T10:00:20Z");

    @Autowired
    private StringRedisTemplate redis;

    private final MutableTestClock clock = new MutableTestClock(AT);

    @AfterEach
    void removeCounters() {
        Set<String> keys = redis.keys(FixedWindowRateLimiter.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void TD53_underTheLimit_requestsPass_andOverItTheRestOfTheWindowIsGiven() {
        FixedWindowRateLimiter limiter = limiter();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("login", "ip:203.0.113.7", 3).allowed())
                    .isTrue();
        }
        FixedWindowRateLimiter.Decision refused = limiter.tryAcquire("login", "ip:203.0.113.7", 3);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(40);
    }

    @Test
    void TD53_theNextWindow_startsFromZero() {
        FixedWindowRateLimiter limiter = limiter();
        limiter.tryAcquire("login", "ip:203.0.113.7", 1);
        assertThat(limiter.tryAcquire("login", "ip:203.0.113.7", 1).allowed()).isFalse();

        clock.set(Instant.parse("2026-10-04T10:01:00Z"));

        assertThat(limiter.tryAcquire("login", "ip:203.0.113.7", 1).allowed()).isTrue();
    }

    @Test
    void TD53_subjectsAndRules_areCountedApart() {
        FixedWindowRateLimiter limiter = limiter();
        limiter.tryAcquire("login", "ip:203.0.113.7", 1);

        assertThat(limiter.tryAcquire("login", "ip:203.0.113.8", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("auth", "ip:203.0.113.7", 1).allowed()).isTrue();
    }

    /** Two backend instances over one Redis count together. */
    @Test
    void TD53_twoInstances_shareTheCount() {
        FixedWindowRateLimiter first = limiter();
        FixedWindowRateLimiter second = limiter();

        assertThat(first.tryAcquire("api", "user:42", 2).allowed()).isTrue();
        assertThat(second.tryAcquire("api", "user:42", 2).allowed()).isTrue();
        assertThat(first.tryAcquire("api", "user:42", 2).allowed()).isFalse();
    }

    @Test
    void TD53_aCounter_expiresWithItsWindow() {
        limiter().tryAcquire("login", "ip:203.0.113.7", 5);

        String key =
                redis.keys(FixedWindowRateLimiter.KEY_PREFIX + "*").iterator().next();
        assertThat(key).isEqualTo("rl:login:ip:203.0.113.7:" + AT.getEpochSecond() / 60);
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    /** Redis down: the request passes rather than the API going down with the cache (ADR-005). */
    @Test
    void TD53_withRedisUnreachable_requestsPass() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(200))
                        .build());
        nowhere.afterPropertiesSet();
        try {
            StringRedisTemplate unreachable = new StringRedisTemplate(nowhere);
            FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(unreachable, clock, Duration.ofMinutes(1));

            assertThat(limiter.tryAcquire("login", "ip:203.0.113.7", 1).allowed())
                    .isTrue();
            assertThat(limiter.tryAcquire("login", "ip:203.0.113.7", 1).allowed())
                    .isTrue();
        } finally {
            nowhere.destroy();
        }
    }

    private FixedWindowRateLimiter limiter() {
        return new FixedWindowRateLimiter(redis, clock, Duration.ofMinutes(1));
    }
}
