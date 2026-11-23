package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.config.MarketApiProperties;
import com.cryptopilot.market.dto.response.ChartCandlesResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.support.MutableTestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trading chart's candles at any timeframe (TR-35).
 *
 * <p>Invariants: (1) a stored timeframe is read from the database and never asks the exchange for its closed candles;
 * (2) any other timeframe is read from the exchange, once per page while the cache keeps it; (3) the forming candle is
 * the exchange's, marked not closed, last, only on the latest page and only when asked for; (4) 1s is Spot only, and
 * an unknown timeframe, a limit outside 1–1000 or a future {@code to} is refused; (5) the exchange unavailable is a
 * 503; (6) the exchange is always asked for a full page, cut to {@code limit} after the cache, so limits share one
 * page; (7) every {@code to} inside one candle of the grid shares one page; (8) a miss past the chart's share of the
 * weight budget is a 503, while a cached page is still served.
 *
 * <p>Rule: TR-35; BR-08.
 */
class ChartCandleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-22T08:30:20Z");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000a035");

    private final MarketRequests requests = mock(MarketRequests.class);
    private final OhlcvRepository stored = mock(OhlcvRepository.class);
    private final BinanceRestClient exchange = mock(BinanceRestClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final Map<String, String> cache = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final ChartCandleServiceImpl service = new ChartCandleServiceImpl(
            requests,
            stored,
            exchange,
            redis,
            JsonMapper.builder().build(),
            new MarketApiProperties(500, 1000, Duration.ofDays(1), Duration.ofDays(7), 60),
            new MutableTestClock(NOW));

    @BeforeEach
    void setUp() {
        CryptoPair pair = mock(CryptoPair.class);
        when(pair.getId()).thenReturn(PAIR);
        when(pair.getSymbol()).thenReturn("BTCUSDT");
        when(requests.enabledPair(any(), anyString())).thenReturn(pair);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cache.get((String) call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> {
                    cache.put(call.getArgument(0), call.getArgument(1));
                    ttls.put(call.getArgument(0), call.getArgument(2));
                    return null;
                })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void TR35_aStoredTimeframe_isReadFromTheDatabase_withTheFormingCandleFromTheExchange() {
        when(stored.closedCandles(eq(PAIR), eq(MarketType.SPOT), eq("1h"), isNull(), eq(NOW), eq(2)))
                .thenReturn(List.of(storedCandle("2026-11-22T06:00:00Z"), storedCandle("2026-11-22T07:00:00Z")));
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "1h", null, null, 1000))
                .thenReturn(List.of(kline("2026-11-22T08:00:00Z", Duration.ofHours(1))));

        ChartCandlesResponse response = service.candles("spot", "btcusdt", "1h", 2, null, true);

        assertThat(response.source()).isEqualTo("STORED");
        assertThat(response.candles())
                .extracting(ChartCandlesResponse.Candle::closed)
                .containsExactly(true, true, false);
        assertThat(response.candles().getLast().openTime()).isEqualTo(Instant.parse("2026-11-22T08:00:00Z"));
    }

    @Test
    void TR35_aPageOfThePast_hasNoFormingCandle_andAStoredOneAsksTheExchangeNothing() {
        Instant to = Instant.parse("2026-11-20T00:00:00Z");
        when(stored.closedCandles(any(), any(), any(), any(), eq(to), anyInt()))
                .thenReturn(List.of(storedCandle("2026-11-19T23:00:00Z")));

        ChartCandlesResponse response = service.candles("spot", "BTCUSDT", "1h", 10, to, true);

        assertThat(response.candles()).hasSize(1).allMatch(ChartCandlesResponse.Candle::closed);
        verify(exchange, never()).klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void TR35_anotherTimeframe_isReadFromTheExchange_onceWhileTheCacheKeepsIt() {
        when(exchange.klines(BinanceVenue.USD_M_FUTURES, "BTCUSDT", "3m", null, null, 1000))
                .thenReturn(List.of(
                        kline("2026-11-22T08:24:00Z", Duration.ofMinutes(3)),
                        kline("2026-11-22T08:30:00Z", Duration.ofMinutes(3))));

        ChartCandlesResponse first = service.candles("futures", "BTCUSDT", "3m", 2, null, true);
        ChartCandlesResponse second = service.candles("futures", "BTCUSDT", "3m", 2, null, true);

        assertThat(first.source()).isEqualTo("EXCHANGE");
        assertThat(first.candles())
                .extracting(ChartCandlesResponse.Candle::closed)
                .containsExactly(true, false);
        assertThat(second).isEqualTo(first);
        verify(exchange, times(1)).klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt());
        assertThat(ttls.values()).containsExactly(ChartCandleServiceImpl.LIVE_TTL);
    }

    @Test
    void TR35_withoutTheFormingCandle_onlyClosedOnesAreAnswered() {
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "1M", null, null, 1000))
                .thenReturn(List.of(
                        kline("2026-10-01T00:00:00Z", Duration.ofDays(31)),
                        kline("2026-11-01T00:00:00Z", Duration.ofDays(30))));

        assertThat(service.candles("spot", "BTCUSDT", "1M", 2, null, false).candles())
                .singleElement()
                .satisfies(candle -> assertThat(candle.closed()).isTrue());
    }

    @Test
    void TR35_aPageOfThePast_endsBeforeTo_andIsKeptLonger() {
        // Friday 20 November: the week opened Monday 16 November, so the page ends before Monday 23 November.
        Instant to = Instant.parse("2026-11-20T00:00:00Z");
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "1w", null, Instant.parse("2026-11-22T23:59:59.999Z"), 1000))
                .thenReturn(List.of(kline("2026-11-10T00:00:00Z", Duration.ofDays(7))));

        assertThat(service.candles("spot", "BTCUSDT", "1w", 5, to, true).candles())
                .hasSize(1);
        assertThat(ttls.values()).containsExactly(ChartCandleServiceImpl.PAST_TTL);
    }

    @ParameterizedTest(name = "{0} {1} limit {2}")
    @CsvSource({"futures, 1s, 10", "spot, 2m, 10", "spot, 1H, 10", "spot, 1m, 0", "spot, 1m, 1001"})
    void TR35_aTimeframeOrLimitOutsideTheChart_isRefused(String market, String tf, int limit) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.candles(market, "BTCUSDT", tf, limit, null, true))
                .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void TR35_oneSecondOnSpot_andAFutureTo() {
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "1s", null, null, 1000))
                .thenReturn(List.of());

        assertThat(service.candles("spot", "BTCUSDT", "1s", 3, null, true).candles())
                .isEmpty();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.candles("spot", "BTCUSDT", "1m", 3, NOW.plusSeconds(1), true));
    }

    @Test
    void TR35_theExchangeUnavailable_isA503() {
        when(exchange.klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("banned"));
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.candles("spot", "BTCUSDT", "5m", 3, null, true))
                .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MARKET_DATA_UNAVAILABLE));
    }

    @Test
    void TR35_twoTosInsideOneCandle_askTheExchangeOnce() {
        Instant first = Instant.parse("2026-11-21T10:01:10Z");
        Instant second = Instant.parse("2026-11-21T10:04:59Z");
        Instant end = Instant.parse("2026-11-21T10:05:00Z");
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "5m", null, end.minusMillis(1), 1000))
                .thenReturn(List.of(
                        kline("2026-11-21T09:55:00Z", Duration.ofMinutes(5)),
                        kline("2026-11-21T10:00:00Z", Duration.ofMinutes(5))));

        ChartCandlesResponse a = service.candles("spot", "BTCUSDT", "5m", 10, first, false);
        ChartCandlesResponse b = service.candles("spot", "BTCUSDT", "5m", 10, second, false);

        assertThat(b).isEqualTo(a);
        assertThat(a.candles()).hasSize(2);
        verify(exchange, times(1)).klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt());
        assertThat(cache).containsOnlyKeys("chart:SPOT:BTCUSDT:5m:" + end.toEpochMilli());
        assertThat(ttls.values()).containsExactly(ChartCandleServiceImpl.PAST_TTL);
    }

    @Test
    void TR35_differentLimits_shareOneCachedPage_cutAfterTheCache() {
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "3m", null, null, 1000))
                .thenReturn(List.of(
                        kline("2026-11-22T08:21:00Z", Duration.ofMinutes(3)),
                        kline("2026-11-22T08:24:00Z", Duration.ofMinutes(3)),
                        kline("2026-11-22T08:27:00Z", Duration.ofMinutes(3)),
                        kline("2026-11-22T08:30:00Z", Duration.ofMinutes(3))));

        ChartCandlesResponse two = service.candles("spot", "BTCUSDT", "3m", 2, null, true);
        ChartCandlesResponse ten = service.candles("spot", "BTCUSDT", "3m", 10, null, true);

        assertThat(two.candles())
                .extracting(ChartCandlesResponse.Candle::openTime)
                .containsExactly(Instant.parse("2026-11-22T08:27:00Z"), Instant.parse("2026-11-22T08:30:00Z"));
        assertThat(ten.candles()).hasSize(4);
        verify(exchange, times(1)).klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt());
    }

    @ParameterizedTest(name = "{0} to {1} ends at {2}")
    @CsvSource({
        "1M, 2026-02-14T05:00:00Z, 2026-03-01T00:00:00Z",
        "1M, 2026-11-01T00:00:00Z, 2026-11-01T00:00:00Z",
        "1w, 2026-11-22T23:00:00Z, 2026-11-23T00:00:00Z",
        "1w, 2026-11-16T00:00:00Z, 2026-11-16T00:00:00Z",
        "3d, 2026-11-21T00:00:01Z, 2026-11-22T00:00:00Z",
        "3d, 2026-11-21T00:00:00Z, 2026-11-21T00:00:00Z",
        "1s, 2026-11-21T10:00:00.250Z, 2026-11-21T10:00:01Z",
        "12h, 2026-11-21T13:00:00Z, 2026-11-22T00:00:00Z",
        "1m, 2026-11-21T10:00:00Z, 2026-11-21T10:00:00Z"
    })
    void TR35_aPageEnds_atTheNextBoundaryOfTheGrid(String tf, Instant to, Instant end) {
        assertThat(ChartCandleServiceImpl.pageEnd(
                        com.cryptopilot.market.model.enums.ChartTimeframe.fromCode(tf)
                                .orElseThrow(),
                        to))
                .isEqualTo(end);
    }

    @Test
    void TR35_pastTheChartsWeightShare_aMissIsA503_butACachedPageIsServed() {
        when(exchange.klines(BinanceVenue.SPOT, "BTCUSDT", "5m", null, null, 1000))
                .thenReturn(List.of(kline("2026-11-22T08:30:00Z", Duration.ofMinutes(5))));
        when(exchange.weightPerMinute(BinanceVenue.SPOT)).thenReturn(6000);
        when(exchange.usedWeightThisMinute(BinanceVenue.SPOT)).thenReturn(3599, 3600);

        ChartCandlesResponse first = service.candles("spot", "BTCUSDT", "5m", 3, null, true);

        assertThat(service.candles("spot", "BTCUSDT", "5m", 3, null, true)).isEqualTo(first);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.candles("spot", "BTCUSDT", "1m", 3, null, true))
                .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MARKET_DATA_UNAVAILABLE));
        verify(exchange, times(1)).klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt());
    }

    private static StoredCandle storedCandle(String open) {
        Instant at = Instant.parse(open);
        return new StoredCandle(
                at,
                at.plus(Duration.ofHours(1)).minusMillis(1),
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.TEN,
                5);
    }

    private static Kline kline(String open, Duration length) {
        Instant at = Instant.parse(open);
        return new Kline(
                at,
                at.plus(length).minusMillis(1),
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                new BigDecimal("5"),
                BigDecimal.ONE,
                BigDecimal.TEN,
                7);
    }
}
