package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.service.SyntheticKlines;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The closed 1-minute candles a restart replays, fetched from a local endpoint that answers like the exchange's kline
 * endpoint: pages, the forming candle left out, refusals returned with the time to ask again.
 *
 * <p>Rule: NSF-07, BR-08; A-04; TECHNICAL_DESIGN 7.1.2.
 */
class MinuteKlineServiceImplTest {

    private static final String SPOT_KLINES = "/api/v3/klines";
    private static final String FUTURES_KLINES = "/fapi/v1/klines";
    private static final Instant NOW = Instant.parse("2026-10-03T08:10:30Z");
    private static final Instant M0 = Instant.parse("2026-10-03T08:00:00Z");
    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CryptoPairRepository pairs = mock(CryptoPairRepository.class);
    private final SyntheticKlines klines = new SyntheticKlines(NOW).listed("BTCUSDT", M0.minus(Duration.ofDays(1)));
    private StubExchange exchange;
    private BinanceRestClient client;
    private MinuteKlineServiceImpl service;
    private CryptoPair pair;

    @BeforeEach
    void setUp() throws Exception {
        exchange = new StubExchange();
        exchange.respond(SPOT_KLINES, klines::answer);
        exchange.respond(FUTURES_KLINES, klines::answer);
        client = clientFor(exchange.baseUrl());
        service = new MinuteKlineServiceImpl(client, pairs, properties(3, 2), clock);
        pair = CryptoPair.register(UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT");
        pair.applyFilters(MarketType.SPOT, FILTERS);
        pair.enable(MarketType.SPOT);
        when(pairs.findById(pair.getId())).thenReturn(Optional.of(pair));
    }

    @AfterEach
    void tearDown() {
        client.close();
        exchange.close();
    }

    @Test
    void A04_aPage_holdsClosedCandlesFromTheGivenOpenTime_oldestFirst_atMostThePageSize() {
        MinuteKlineBatch batch = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), M0);

        assertThat(batch.refused()).isFalse();
        assertThat(batch.klines()).extracting(MinuteKline::openTime).containsExactly(M0, minute(1), minute(2));
        assertThat(batch.klines()).allSatisfy(kline -> {
            assertThat(kline.closed()).isTrue();
            assertThat(kline.market()).isEqualTo(MarketType.SPOT);
            assertThat(kline.pairId()).isEqualTo(pair.getId());
            assertThat(kline.low()).isEqualByComparingTo("0.00000001");
            assertThat(kline.high()).isEqualByComparingTo("123456.12345678");
            assertThat(kline.eventTime())
                    .isEqualTo(kline.openTime().plusSeconds(60).minusMillis(1));
        });
        assertThat(exchange.requests().getLast().getQuery())
                .contains("interval=1m")
                .contains("limit=3");
    }

    @Test
    void BR08_theCandleStillForming_isNeverReturned() {
        MinuteKlineBatch last = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), minute(9));
        MinuteKlineBatch forming = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), minute(10));

        assertThat(last.klines()).extracting(MinuteKline::openTime).containsExactly(minute(9));
        assertThat(forming.klines()).isEmpty();
        assertThat(forming.refused()).isFalse();
    }

    @Test
    void A04_paging_fromTheCandleAfterTheLast_coversEveryClosedCandleOnce() {
        Instant cursor = M0;
        int pages = 0;
        List<Instant> seen = new ArrayList<>();
        while (true) {
            MinuteKlineBatch batch = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), cursor);
            if (batch.klines().isEmpty()) {
                break;
            }
            pages++;
            batch.klines().forEach(kline -> seen.add(kline.openTime()));
            cursor = batch.klines().getLast().openTime().plus(Duration.ofMinutes(1));
        }

        assertThat(pages).isEqualTo(4);
        assertThat(seen).hasSize(10).doesNotHaveDuplicates().first().isEqualTo(M0);
        assertThat(seen).last().isEqualTo(minute(9));
    }

    @Test
    void A04_aFuturesPair_isFetchedFromTheFuturesEndpointWithItsPageSize() {
        pair.applyFilters(MarketType.FUTURES, FILTERS);
        pair.enable(MarketType.FUTURES);

        MinuteKlineBatch batch = service.closedMinuteKlines(MarketType.FUTURES, pair.getId(), M0);

        assertThat(batch.klines()).hasSize(2).allMatch(kline -> kline.market() == MarketType.FUTURES);
        assertThat(exchange.hits(FUTURES_KLINES)).isEqualTo(1);
    }

    @Test
    void BR07_aPairNotEnabledOnTheMarket_hasNoCandles_andTheExchangeIsNotAsked() {
        MinuteKlineBatch futures = service.closedMinuteKlines(MarketType.FUTURES, pair.getId(), M0);
        MinuteKlineBatch unknown = service.closedMinuteKlines(MarketType.SPOT, UUID.randomUUID(), M0);

        assertThat(futures.klines()).isEmpty();
        assertThat(unknown.klines()).isEmpty();
        assertThat(exchange.requests()).isEmpty();
    }

    @Test
    void BR10_aRateLimit_isReturnedWithTheExchangesRetryTime() {
        exchange.on(SPOT_KLINES, Answer.status(429).withHeader("Retry-After", "30"));

        MinuteKlineBatch batch = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), M0);

        assertThat(batch.refused()).isTrue();
        assertThat(batch.retryAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(batch.klines()).isEmpty();
    }

    @Test
    void BR10_anOutage_isReturnedWithTheNextMinute() {
        exchange.on(SPOT_KLINES, Answer.status(503));

        MinuteKlineBatch batch = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), M0);

        assertThat(batch.retryAt()).isEqualTo(Instant.parse("2026-10-03T08:11:00Z"));
    }

    @Test
    void A04_aRequestTheExchangeRejects_endsWithAnEmptyPage() {
        exchange.on(SPOT_KLINES, Answer.status(400));

        MinuteKlineBatch batch = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), M0);

        assertThat(batch.refused()).isFalse();
        assertThat(batch.klines()).isEmpty();
    }

    @Test
    void BR10_onceTheBackfillShareOfTheWeightIsUsed_theNextPageWaitsForTheNextMinute() {
        klines.usedWeight(3000);

        MinuteKlineBatch first = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), M0);
        MinuteKlineBatch second = service.closedMinuteKlines(MarketType.SPOT, pair.getId(), minute(3));

        assertThat(first.klines()).hasSize(3);
        assertThat(second.retryAt()).isEqualTo(Instant.parse("2026-10-03T08:11:00Z"));
        assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(1);
    }

    private static Instant minute(long n) {
        return M0.plus(Duration.ofMinutes(n));
    }

    private static CandleBackfillProperties properties(int spotPage, int futuresPage) {
        return new CandleBackfillProperties(
                false,
                "0 1 * * * *",
                ZoneOffset.UTC,
                50,
                new CandleBackfillProperties.Depth(
                        Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(5), Duration.ofDays(10)),
                new CandleBackfillProperties.PageSize(spotPage, futuresPage),
                Duration.ofDays(7));
    }

    private BinanceRestClient clientFor(URI base) {
        return new BinanceRestClient(
                new BinanceClientProperties(
                        new BinanceClientProperties.Venue(base, 6000),
                        new BinanceClientProperties.Venue(base, 2400),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        80,
                        Duration.ofMinutes(2),
                        new BinanceClientProperties.Retry(
                                0, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO),
                        new BinanceClientProperties.CircuitBreaker(50, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                new InMemoryBinanceBans());
    }
}
