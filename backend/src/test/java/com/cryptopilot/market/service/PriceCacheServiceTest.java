package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.config.PriceCacheProperties;
import com.cryptopilot.market.model.CachedPrice;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.service.impl.PriceCacheServiceImpl;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The latest-price cache against a real Redis: what a read finds, misses and reports expired, that an older value never
 * replaces a newer one — in memory or in Redis — and that a Redis nobody can reach costs a counted failure and nothing
 * else. Time is a test clock; nothing waits.
 *
 * <p>Rule: NSF-03; BR-11; TECHNICAL_DESIGN 5.6; ADR-005.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PriceCacheServiceTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final PriceCacheProperties PROPERTIES =
            new PriceCacheProperties(true, Duration.ofMinutes(5), Duration.ofMillis(250));

    @Autowired
    private StringRedisTemplate redis;

    private final MutableTestClock clock = new MutableTestClock(AT);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private PriceCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new PriceCacheServiceImpl(redis, PROPERTIES, clock, meters);
    }

    @AfterEach
    void clear() {
        var keys = redis.keys("px:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** A Spot ticker, flushed: found under px:SPOT:BTCUSDT with its values, its source instant and the 5-minute TTL. */
    @Test
    void NSF03_aFlushedTicker_isFoundWithItsSourceInstant() {
        cache.record(MarketType.SPOT, ticker("63050.25", AT));

        assertThat(cache.flush()).isOne();
        PriceLookup found = cache.latest(MarketType.SPOT, "BTCUSDT");

        assertThat(found.status()).isEqualTo(PriceLookup.Status.FOUND);
        CachedPrice price = found.price().orElseThrow();
        assertThat(price.lastPrice()).isEqualByComparingTo("63050.25");
        assertThat(price.bestBid()).isEqualByComparingTo("63050.00");
        assertThat(price.bestAsk()).isEqualByComparingTo("63050.50");
        assertThat(price.markPrice()).isNull();
        assertThat(price.sourceTime()).isEqualTo(AT);
        assertThat(redis.getExpire("px:SPOT:BTCUSDT")).isBetween(290L, 300L);
    }

    /** BR-11: a futures mark price keeps the source's next funding time; the Spot entry of the pair is another key. */
    @Test
    void BR11_aMarkPrice_isCachedWithTheSourcesNextFundingTime() {
        cache.record(MarketType.FUTURES, markPrice("63055.5", AT));
        cache.flush();

        CachedPrice price = cache.latest(MarketType.FUTURES, "BTCUSDT").price().orElseThrow();

        assertThat(price.markPrice()).isEqualByComparingTo("63055.5");
        assertThat(price.indexPrice()).isEqualByComparingTo("63050");
        assertThat(price.fundingRate()).isEqualByComparingTo("0.0001");
        assertThat(price.nextFundingTime()).isEqualTo(Instant.parse("2026-09-25T16:00:00Z"));
        assertThat(cache.latest(MarketType.SPOT, "BTCUSDT").status()).isEqualTo(PriceLookup.Status.MISSING);
    }

    /** Nothing cached for the pair: MISSING, with no price. */
    @Test
    void NSF03_anUnknownPair_isMissing() {
        PriceLookup lookup = cache.latest(MarketType.SPOT, "NOPEUSDT");

        assertThat(lookup.status()).isEqualTo(PriceLookup.Status.MISSING);
        assertThat(lookup.price()).isEmpty();
    }

    /** Older than the TTL by the source's instant: EXPIRED, returned with that instant, never as FOUND. */
    @Test
    void TD56_aPriceOlderThanTheTtl_isExpired_withItsInstant() {
        cache.record(MarketType.SPOT, ticker("63000", AT));
        cache.flush();

        clock.set(AT.plus(Duration.ofMinutes(5)));
        assertThat(cache.latest(MarketType.SPOT, "BTCUSDT").status()).isEqualTo(PriceLookup.Status.FOUND);
        clock.set(AT.plus(Duration.ofMinutes(5)).plusMillis(1));
        PriceLookup expired = cache.latest(MarketType.SPOT, "BTCUSDT");

        assertThat(expired.status()).isEqualTo(PriceLookup.Status.EXPIRED);
        assertThat(expired.price().orElseThrow().sourceTime()).isEqualTo(AT);
    }

    /** Before a flush, a message older than the one waiting is dropped. */
    @Test
    void NSF03_anOlderMessageArrivingLate_neverReplacesTheWaitingOne() {
        cache.record(MarketType.SPOT, ticker("63100", AT.plusSeconds(2)));
        cache.record(MarketType.SPOT, ticker("63000", AT.plusSeconds(1)));

        cache.flush();

        CachedPrice price = cache.latest(MarketType.SPOT, "BTCUSDT").price().orElseThrow();
        assertThat(price.lastPrice()).isEqualByComparingTo("63100");
        assertThat(price.sourceTime()).isEqualTo(AT.plusSeconds(2));
    }

    /** After a flush, an older message written later is refused by Redis itself; an equal or newer one is written. */
    @Test
    void NSF03_anOlderMessageFlushedLater_neverReplacesTheCachedOne() {
        cache.record(MarketType.SPOT, ticker("63100", AT.plusSeconds(2)));
        assertThat(cache.flush()).isOne();

        cache.record(MarketType.SPOT, ticker("63000", AT.plusSeconds(1)));
        assertThat(cache.flush()).as("refused as older").isZero();
        assertThat(cache.latest(MarketType.SPOT, "BTCUSDT")
                        .price()
                        .orElseThrow()
                        .lastPrice())
                .isEqualByComparingTo("63100");

        cache.record(MarketType.SPOT, ticker("63200", AT.plusSeconds(3)));
        assertThat(cache.flush()).isOne();
        assertThat(cache.latest(MarketType.SPOT, "BTCUSDT")
                        .price()
                        .orElseThrow()
                        .lastPrice())
                .isEqualByComparingTo("63200");
    }

    /** Only tickers and mark prices are cached; a kline is ignored. */
    @Test
    void NSF03_aKline_isNotAPrice() {
        cache.record(
                MarketType.SPOT,
                new KlineMessage(
                        "BTCUSDT",
                        com.cryptopilot.market.client.MarketInterval.ONE_HOUR,
                        new com.cryptopilot.market.client.Kline(
                                AT,
                                AT.plusSeconds(3599),
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                1),
                        false,
                        AT));

        assertThat(cache.flush()).isZero();
    }

    /**
     * ADR-005: with Redis unreachable, recording never throws, a flush counts its failure and keeps the values waiting,
     * and a read answers UNAVAILABLE.
     */
    @Test
    void ADR005_anUnreachableRedis_isCountedAndNeverThrown() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(500))
                        .build());
        nowhere.afterPropertiesSet();
        try {
            StringRedisTemplate down = new StringRedisTemplate(nowhere);
            PriceCacheService unreachable = new PriceCacheServiceImpl(down, PROPERTIES, clock, meters);

            assertThatCode(() -> unreachable.record(MarketType.SPOT, ticker("63000", AT)))
                    .doesNotThrowAnyException();
            assertThat(unreachable.flush()).isZero();
            assertThat(unreachable.flush())
                    .as("the value still waits and is tried again")
                    .isZero();
            assertThat(meters.counter("cryptopilot.market.price.cache.failures").count())
                    .isEqualTo(2.0);
            assertThat(unreachable.latest(MarketType.SPOT, "BTCUSDT").status())
                    .isEqualTo(PriceLookup.Status.UNAVAILABLE);
            assertThat(meters.counter("cryptopilot.market.price.cache.failures").count())
                    .isEqualTo(3.0);
        } finally {
            nowhere.destroy();
        }
    }

    private static TickerMessage ticker(String last, Instant at) {
        BigDecimal price = new BigDecimal(last);
        return new TickerMessage(
                "BTCUSDT",
                price,
                new BigDecimal("63050.00"),
                new BigDecimal("63050.50"),
                price,
                price,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN,
                at);
    }

    private static MarkPriceMessage markPrice(String mark, Instant at) {
        return new MarkPriceMessage(
                "BTCUSDT",
                new BigDecimal(mark),
                new BigDecimal("63050"),
                new BigDecimal("0.0001"),
                Instant.parse("2026-09-25T16:00:00Z"),
                at);
    }
}
