package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.config.PriceCacheProperties;
import com.cryptopilot.market.model.CachedPrice;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.service.PriceCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * The latest prices in Redis (TECHNICAL_DESIGN 5.6): one hash {@code px:{market}:{symbol}} per pair and market, with
 * the fields {@code last}, {@code bid}, {@code ask}, {@code mark}, {@code index}, {@code funding},
 * {@code nextFundingTime} and {@code updatedAt} — the exchange's own instant for the values, in epoch milliseconds.
 *
 * <h2>The stream never waits for Redis</h2>
 *
 * <p>{@link #record} runs on the stream reader's thread and only writes a map entry per pair: the newest value
 * waiting, by the exchange's instant. {@link #flush}, on a scheduler, writes what waits. A Redis that is slow or down
 * therefore costs the flush thread its timeout and a counted, logged failure; the stream, the candles NSF-03 stores
 * and the snapshots it writes are not touched (ADR-005: Redis is a cache).
 *
 * <h2>An older value never replaces a newer one</h2>
 *
 * <p>Twice over: in memory, where a value older than the one waiting is dropped, and in Redis, where one script
 * compares {@code updatedAt} and writes only when the new value is not older, then sets the time to live. A message
 * that arrives late, after a reconnection, cannot put an old price back.
 *
 * <h2>A price is never served as current when it is not</h2>
 *
 * <p>Redis drops an entry {@code ttl} after its last write. A read also compares the exchange's instant with now and
 * answers {@code EXPIRED}, with the price and its instant, when it is older than {@code ttl}; a missing entry is
 * {@code MISSING}, an unreadable cache {@code UNAVAILABLE}.
 *
 * <p>Rule: NSF-03; BR-11; SRS 4.2.3; TECHNICAL_DESIGN 5.6; ADR-005.
 * <p>Reference: Redis. <i>Redis documentation</i>, "EVAL" (a script runs atomically: no other command runs between its
 * read and its write).
 */
@Service
public class PriceCacheServiceImpl implements PriceCacheService {

    private static final Logger log = LoggerFactory.getLogger(PriceCacheService.class);

    /** The key prefix of TECHNICAL_DESIGN 5.6. */
    static final String KEY_PREFIX = "px:";

    /** The name of the counter of failed writes and reads. */
    static final String FAILURES = "cryptopilot.market.price.cache.failures";

    /**
     * Writes the fields and the time to live unless the cached value is newer. ARGV: updatedAt, ttl in milliseconds,
     * then field/value pairs. Answers 1 when written, 0 when the cached value was newer.
     */
    private static final RedisScript<Long> WRITE_IF_NEWER = RedisScript.of("""
            local cached = redis.call('HGET', KEYS[1], 'updatedAt')
            if cached and tonumber(cached) > tonumber(ARGV[1]) then
                return 0
            end
            redis.call('HSET', KEYS[1], 'updatedAt', ARGV[1], unpack(ARGV, 3))
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final Map<String, CachedPrice> waiting = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final PriceCacheProperties properties;
    private final Clock clock;
    private final Counter failures;

    public PriceCacheServiceImpl(
            StringRedisTemplate redis, PriceCacheProperties properties, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.failures = Counter.builder(FAILURES)
                .description("Latest-price cache operations that failed")
                .register(meters);
    }

    @Override
    public void record(MarketType market, StreamMessage message) {
        CachedPrice price =
                switch (message) {
                    case TickerMessage ticker ->
                        new CachedPrice(
                                market,
                                ticker.symbol(),
                                ticker.lastPrice(),
                                ticker.bestBidPrice(),
                                ticker.bestAskPrice(),
                                null,
                                null,
                                null,
                                null,
                                ticker.eventTime());
                    case MarkPriceMessage mark ->
                        new CachedPrice(
                                market,
                                mark.symbol(),
                                null,
                                null,
                                null,
                                mark.markPrice(),
                                mark.indexPrice(),
                                mark.fundingRate(),
                                mark.nextFundingTime(),
                                mark.eventTime());
                    default -> null;
                };
        if (price != null) {
            waiting.merge(key(market, price.symbol()), price, PriceCacheServiceImpl::newer);
        }
    }

    @Override
    public int flush() {
        int written = 0;
        for (String key : List.copyOf(waiting.keySet())) {
            CachedPrice price = waiting.remove(key);
            if (price == null) {
                continue;
            }
            try {
                Long result = redis.execute(WRITE_IF_NEWER, List.of(key), arguments(price));
                written += result != null && result == 1L ? 1 : 0;
            } catch (RuntimeException failure) {
                failures.increment();
                waiting.merge(key, price, PriceCacheServiceImpl::newer);
                log.warn(
                        "Latest-price cache write failed; {} values wait for the next flush: {}",
                        waiting.size(),
                        failure.toString());
                return written;
            }
        }
        return written;
    }

    @Override
    public PriceLookup latest(MarketType market, String symbol) {
        Map<Object, Object> fields;
        try {
            fields = redis.opsForHash().entries(key(market, symbol));
        } catch (RuntimeException failure) {
            failures.increment();
            log.warn("Latest-price cache read failed: {}", failure.toString());
            return PriceLookup.unavailable();
        }
        if (fields == null || fields.get("updatedAt") == null) {
            return PriceLookup.missing();
        }
        CachedPrice price = new CachedPrice(
                market,
                symbol,
                decimal(fields.get("last")),
                decimal(fields.get("bid")),
                decimal(fields.get("ask")),
                decimal(fields.get("mark")),
                decimal(fields.get("index")),
                decimal(fields.get("funding")),
                instant(fields.get("nextFundingTime")),
                instant(fields.get("updatedAt")));
        boolean expired = Duration.between(price.sourceTime(), clock.instant()).compareTo(properties.ttl()) > 0;
        return new PriceLookup(expired ? PriceLookup.Status.EXPIRED : PriceLookup.Status.FOUND, Optional.of(price));
    }

    /** The key of TECHNICAL_DESIGN 5.6. */
    static String key(MarketType market, String symbol) {
        return KEY_PREFIX + market.name() + ":" + symbol;
    }

    private Object[] arguments(CachedPrice price) {
        List<String> arguments = new ArrayList<>();
        arguments.add(String.valueOf(price.sourceTime().toEpochMilli()));
        arguments.add(String.valueOf(properties.ttl().toMillis()));
        field(arguments, "last", price.lastPrice());
        field(arguments, "bid", price.bestBid());
        field(arguments, "ask", price.bestAsk());
        field(arguments, "mark", price.markPrice());
        field(arguments, "index", price.indexPrice());
        field(arguments, "funding", price.fundingRate());
        if (price.nextFundingTime() != null) {
            arguments.add("nextFundingTime");
            arguments.add(String.valueOf(price.nextFundingTime().toEpochMilli()));
        }
        return arguments.toArray();
    }

    private static void field(List<String> arguments, String name, BigDecimal value) {
        if (value != null) {
            arguments.add(name);
            arguments.add(value.toPlainString());
        }
    }

    private static CachedPrice newer(CachedPrice waiting, CachedPrice arriving) {
        return arriving.sourceTime().isBefore(waiting.sourceTime()) ? waiting : arriving;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.toString()));
    }
}
