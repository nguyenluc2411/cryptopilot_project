package com.cryptopilot.common.web;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Counts requests per subject in fixed windows held in Redis (ADR-014). The key is
 * {@code rl:{rule}:{subject}:{window}}, the window being the number of whole windows since the epoch by the injected
 * clock; one script increments it and sets its expiry on the first hit, so a key never outlives its window and two
 * instances share the count.
 *
 * <p>When Redis cannot be reached the request is allowed: Redis is a cache here (ADR-005), and its loss must not turn
 * into a sign-in outage. The failure is logged at most once a minute.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3; ADR-014.
 *
 * <p>Reference: Nottingham, M. &amp; Fielding, R. (2012). RFC 6585: Additional HTTP Status Codes, section 4. IETF.
 */
public final class FixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

    static final String KEY_PREFIX = "rl:";

    private static final RedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count""", Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final long windowSeconds;
    private final AtomicLong lastFailureLogged = new AtomicLong(Long.MIN_VALUE);

    public FixedWindowRateLimiter(StringRedisTemplate redis, Clock clock, Duration window) {
        this.redis = redis;
        this.clock = clock;
        this.windowSeconds = window.toSeconds();
    }

    /** Whether one more request may pass, and if not, how long until the window ends. */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);
    }

    /**
     * Counts this request for the subject under the rule.
     *
     * @param rule the rule's name, part of the key
     * @param subject the client address or the account
     * @param limit the requests allowed in one window
     */
    public Decision tryAcquire(String rule, String subject, int limit) {
        long now = clock.instant().getEpochSecond();
        long window = Math.floorDiv(now, windowSeconds);
        String key = KEY_PREFIX + rule + ":" + subject + ":" + window;
        Long count;
        try {
            count = redis.execute(COUNT, List.of(key), Long.toString(windowSeconds));
        } catch (RuntimeException unavailable) {
            logFailure(now, unavailable);
            return Decision.ALLOWED;
        }
        if (count == null || count <= limit) {
            return Decision.ALLOWED;
        }
        long windowEnd = (window + 1) * windowSeconds;
        return new Decision(false, Math.max(1, windowEnd - now));
    }

    private void logFailure(long now, RuntimeException failure) {
        long last = lastFailureLogged.get();
        if (now - last >= 60 && lastFailureLogged.compareAndSet(last, now)) {
            log.warn("Rate limit not applied: Redis is unavailable; requests are let through", failure);
        }
    }
}
