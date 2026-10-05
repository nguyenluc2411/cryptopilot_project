package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.ReplayLockProperties;
import com.cryptopilot.trading.service.ReplayLockService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * The replay lease in Redis. Each instance writes a random token as the key's value, so it renews and deletes only its
 * own keys: {@code SET NX PX} to take, compare-and-{@code PEXPIRE} to renew, compare-and-{@code DEL} to release.
 *
 * <p>Taking the lease fails open: when Redis cannot be reached the replay runs anyway, because the deployment is one
 * instance (ADR-005) and a replay that waits for the cache would leave fills unmatched. Reading the state fails safe,
 * see {@link #isReplaying}.
 *
 * <p>Rule: NSF-07; D-79, D-85; TECHNICAL_DESIGN 5.6.
 *
 * <p>Reference: Kleppmann, M. (2016). How to do distributed locking.
 */
@Service
public class ReplayLockServiceImpl implements ReplayLockService {

    private static final Logger log = LoggerFactory.getLogger(ReplayLockServiceImpl.class);

    static final String KEY_PREFIX = "rpl:";

    private static final RedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0""", Long.class);

    private static final RedisScript<Long> DELETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0""", Long.class);

    private final StringRedisTemplate redis;
    private final ReplayLockProperties properties;
    private final TaskScheduler scheduler;
    private final String token = UUID.randomUUID().toString();
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> heartbeats;

    public ReplayLockServiceImpl(StringRedisTemplate redis, ReplayLockProperties properties, TaskScheduler scheduler) {
        this.redis = redis;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /** Starts renewing the held keys every {@code heartbeat}. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startHeartbeat() {
        if (heartbeats == null) {
            heartbeats = scheduler.scheduleAtFixedRate(this::heartbeat, properties.heartbeat());
        }
    }

    @Override
    public boolean tryAcquire(MarketType market, UUID pairId) {
        String key = key(market, pairId);
        try {
            Boolean taken = redis.opsForValue().setIfAbsent(key, token, properties.ttl());
            if (Boolean.TRUE.equals(taken) || token.equals(redis.opsForValue().get(key))) {
                held.add(key);
                return true;
            }
            return false;
        } catch (RuntimeException unavailable) {
            log.warn("NSF-07 {} {}: replay lock not taken, Redis unavailable; replaying anyway", market, pairId);
            return true;
        }
    }

    @Override
    public void release(MarketType market, UUID pairId) {
        delete(key(market, pairId));
    }

    @Override
    public void releaseAll() {
        List.copyOf(held).forEach(this::delete);
    }

    @Override
    public boolean isReplaying(MarketType market, UUID pairId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(market, pairId)));
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    @Override
    public int heartbeat() {
        int renewed = 0;
        for (String key : List.copyOf(held)) {
            try {
                Long result = redis.execute(
                        RENEW,
                        List.of(key),
                        token,
                        Long.toString(properties.ttl().toMillis()));
                if (result != null && result == 1) {
                    renewed++;
                } else {
                    held.remove(key);
                    log.warn("NSF-07 replay lock {} expired or was taken over; no longer renewed", key);
                }
            } catch (RuntimeException unavailable) {
                log.warn("NSF-07 replay lock {} not renewed, Redis unavailable; retried at the next heartbeat", key);
            }
        }
        return renewed;
    }

    private void delete(String key) {
        held.remove(key);
        try {
            redis.execute(DELETE, List.of(key), token);
        } catch (RuntimeException unavailable) {
            log.warn("NSF-07 replay lock {} not deleted, Redis unavailable; it expires on its own", key);
        }
    }

    static String key(MarketType market, UUID pairId) {
        return KEY_PREFIX + market.name() + ":" + pairId;
    }
}
