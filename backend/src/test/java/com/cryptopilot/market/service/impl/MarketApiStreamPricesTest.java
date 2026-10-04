package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cryptopilot.market.client.BinanceStreamParser;
import com.cryptopilot.market.client.StreamFrames;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.config.PriceCacheProperties;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.MinuteKlineService;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The current prices trading reads, from frames shaped exactly as the exchange sends them, through the parser, the
 * cache writer and Redis: a Futures stream gives a mark price and never a last price; a Spot stream gives a last
 * price. Nothing here is a hand-built cache entry.
 *
 * <p>Rule: BR-33; NSF-03; TECHNICAL_DESIGN 5.6.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketApiStreamPricesTest {

    /** The event time of {@link StreamFrames#MARK_PRICE_CAPTURED}. */
    private static final Instant CAPTURED_AT = Instant.ofEpochMilli(1790259565000L);

    private static final PriceCacheProperties PROPERTIES =
            new PriceCacheProperties(true, Duration.ofMinutes(5), Duration.ofMillis(250));

    @Autowired
    private StringRedisTemplate redis;

    private final MutableTestClock clock = new MutableTestClock(CAPTURED_AT);
    private final BinanceStreamParser parser =
            new BinanceStreamParser(JsonMapper.builder().build());

    private PriceCacheServiceImpl cache;
    private MarketApiImpl api;

    @BeforeEach
    void setUp() {
        cache = new PriceCacheServiceImpl(redis, PROPERTIES, clock, new SimpleMeterRegistry());
        api = new MarketApiImpl(
                mock(CryptoPairRepository.class),
                mock(LeverageBracketRepository.class),
                cache,
                mock(MinuteKlineService.class));
    }

    @AfterEach
    void clear() {
        Set<String> keys = redis.keys("px:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void BR33_theFuturesStream_givesACurrentMarkPrice_andNoLastPrice() {
        stream(MarketType.FUTURES, StreamFrames.MARK_PRICE_CAPTURED);

        assertThat(api.currentMarkPrice("BTCUSDT")).contains(new BigDecimal("84286.39852899"));
        assertThat(api.currentFundingRate("BTCUSDT")).contains(new BigDecimal("0.00001583"));
        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT"))
                .as("no Futures stream carries a last trade price")
                .isEmpty();
    }

    /** The cache's freshness rule applies to the mark price as to every other value: an old one is not current. */
    @Test
    void BR33_aMarkPriceOlderThanTheCacheWindow_isNotCurrent() {
        stream(MarketType.FUTURES, StreamFrames.MARK_PRICE_CAPTURED);

        clock.set(CAPTURED_AT.plus(PROPERTIES.ttl()).plusSeconds(1));

        assertThat(api.currentMarkPrice("BTCUSDT")).isEmpty();
    }

    @Test
    void BR33_noFuturesStream_givesNoMarkPrice() {
        assertThat(api.currentMarkPrice("BTCUSDT")).isEmpty();
    }

    @Test
    void BR33_theSpotStream_givesACurrentLastPrice() {
        stream(MarketType.SPOT, StreamFrames.ticker("BTCUSDT", CAPTURED_AT));

        assertThat(api.currentLastPrice(MarketType.SPOT, "BTCUSDT")).contains(new BigDecimal("84282.01"));
    }

    private void stream(MarketType market, String frame) {
        StreamMessage message = parser.parse(frame).orElseThrow();
        cache.record(market, message);
        cache.flush();
    }
}
