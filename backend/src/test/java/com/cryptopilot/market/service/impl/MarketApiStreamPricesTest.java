package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cryptopilot.market.client.BinanceStreamParser;
import com.cryptopilot.market.client.StreamFrames;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.config.PriceCacheProperties;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.FuturesMetricsRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.AnalysisService;
import com.cryptopilot.market.service.MinuteKlineService;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                mock(CoinRepository.class),
                mock(LeverageBracketRepository.class),
                cache,
                mock(MinuteKlineService.class),
                mock(CoinWriter.class),
                mock(TradingMarketData.class),
                mock(FuturesMetricsRepository.class),
                mock(AnalysisService.class),
                Clock.systemUTC());
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
                .as("a mark price frame carries no last trade price")
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

    /** TR-04: the batch read gives the same answer as one read per pair, in one round trip. */
    @Test
    void TR04_theBatchRead_givesTheCurrentPrices_andLeavesOutAPairWithoutOne() {
        stream(MarketType.SPOT, StreamFrames.ticker("BTCUSDT", CAPTURED_AT));

        assertThat(api.currentLastPrices(MarketType.SPOT, List.of("BTCUSDT", "ETHUSDT")))
                .containsOnlyKeys("BTCUSDT")
                .containsEntry("BTCUSDT", new BigDecimal("84282.01"));
        assertThat(api.currentLastPrices(MarketType.SPOT, List.of())).isEmpty();
    }

    /**
     * D-111 E2/E3: the close of a futures 1-minute candle update is the Futures last price. The captured candle is
     * 144 ms older than the captured mark price, so both are kept, each with its own instant, and the mark price is
     * unchanged.
     */
    @Test
    void D111_aFuturesMinuteCandle_givesTheLastPrice_besideAnUnchangedMarkPrice() {
        stream(MarketType.FUTURES, StreamFrames.MARK_PRICE_CAPTURED);
        stream(MarketType.FUTURES, StreamFrames.FUTURES_KLINE_CAPTURED);

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).contains(new BigDecimal("84285.90"));
        assertThat(api.currentMarkPrice("BTCUSDT")).contains(new BigDecimal("84286.39852899"));
        assertThat(api.currentFundingRate("BTCUSDT")).contains(new BigDecimal("0.00001583"));
        assertThat(api.currentLastPrices(MarketType.FUTURES, List.of("BTCUSDT", "ETHUSDT")))
                .containsOnlyKeys("BTCUSDT")
                .containsEntry("BTCUSDT", new BigDecimal("84285.90"));
    }

    /**
     * The last price and the mark price each age on their own instant: when the candles stop, the last price stops
     * being current while a mark price that keeps arriving stays current, and the other way round.
     */
    @Test
    void D111_theFuturesLastPrice_andTheMarkPrice_ageEachOnTheirOwnInstant() {
        Instant candleAt = Instant.ofEpochMilli(1790259564856L);
        stream(MarketType.FUTURES, StreamFrames.FUTURES_KLINE_CAPTURED);
        Instant later = candleAt.plus(PROPERTIES.ttl()).plusSeconds(1);
        clock.set(later);
        stream(MarketType.FUTURES, StreamFrames.markPrice("BTCUSDT", later));

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).isEmpty();
        assertThat(api.currentMarkPrice("BTCUSDT")).isPresent();

        clock.set(later.plus(PROPERTIES.ttl()).plusSeconds(2));
        stream(MarketType.FUTURES, StreamFrames.kline("BTCUSDT", MarketInterval.ONE_MINUTE, clock.instant(), false));

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).contains(new BigDecimal("101.20"));
        assertThat(api.currentMarkPrice("BTCUSDT")).isEmpty();
    }

    /** Only the 1-minute candle is a last price; a forming candle of another timeframe is not. */
    @Test
    void D111_aFuturesCandleOfAnotherTimeframe_givesNoLastPrice() {
        stream(MarketType.FUTURES, StreamFrames.kline("BTCUSDT", MarketInterval.ONE_HOUR, CAPTURED_AT, false));

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).isEmpty();
    }

    private void stream(MarketType market, String frame) {
        StreamMessage message = parser.parse(frame).orElseThrow();
        cache.record(market, message);
        cache.flush();
    }
}
