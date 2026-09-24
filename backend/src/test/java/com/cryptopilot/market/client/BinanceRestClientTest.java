package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.support.MutableTestClock;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Binance REST client against a local stand-in for the exchange: what it reads, what it asks for,
 * and what it does when the exchange answers badly (TECHNICAL_DESIGN 7.1.2).
 *
 * <p>No test here reaches Binance. The stand-in is a JDK HTTP server on a free local port, the clock is
 * moved by hand, and the retry back-off is a millisecond, so every case is deterministic and fast.
 *
 * <p>Rule: BR-08, BR-09, BR-10, BR-11; NSF-01, NSF-02, NSF-04; TECHNICAL_DESIGN 5.4, 7.1.1, 7.1.2.
 */
class BinanceRestClientTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:30Z");

    private static final String SPOT_EXCHANGE_INFO = "/api/v3/exchangeInfo";
    private static final String FUTURES_EXCHANGE_INFO = "/fapi/v1/exchangeInfo";
    private static final String SPOT_KLINES = "/api/v3/klines";
    private static final String FUTURES_KLINES = "/fapi/v1/klines";

    private static final String KLINES_BODY = """
            [[1727172000000,"63012.01000000","63100.00000000","62950.10000000","63050.55000000",
              "12.00000001",1727172059999,"756600.12345678",1234,"6.0","378300.0","0"]]""";

    private final MutableTestClock clock = new MutableTestClock(NOW);

    private final InMemoryBinanceBans bans = new InMemoryBinanceBans();

    private StubExchange exchange;

    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        exchange = new StubExchange();
        client = clientFor(exchange.baseUrl(), exchange.baseUrl(), Duration.ofMillis(500));
    }

    @AfterEach
    void stopEverything() {
        client.close();
        exchange.close();
    }

    // ------------------------------------------------------------------ happy paths

    @Nested
    class ReadsTheDocumentedShapes {

        /** NSF-01: status, assets and the three filters, with Spot's NOTIONAL filter for the minimum value. */
        @Test
        void NSF01_spotExchangeInfo_readsStatusAndFilters() {
            exchange.on(SPOT_EXCHANGE_INFO, Answer.ok("""
                    {"timezone":"UTC","serverTime":1727172030000,"rateLimits":[],"symbols":[
                      {"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC","quoteAsset":"USDT","filters":[
                        {"filterType":"PRICE_FILTER","minPrice":"0.01000000","maxPrice":"1000000.00000000","tickSize":"0.01000000"},
                        {"filterType":"LOT_SIZE","minQty":"0.00001000","maxQty":"9000.00000000","stepSize":"0.00001000"},
                        {"filterType":"NOTIONAL","minNotional":"5.00000000","applyMinToMarket":true},
                        {"filterType":"MAX_NUM_ORDERS","maxNumOrders":200}]},
                      {"symbol":"OLDUSDT","status":"BREAK","baseAsset":"OLD","quoteAsset":"USDT","filters":[]}]}"""));

            List<ExchangeSymbol> symbols = client.spotExchangeInfo();

            ExchangeSymbol btc = symbols.get(0);
            assertThat(btc.symbol()).isEqualTo("BTCUSDT");
            assertThat(btc.isTrading()).isTrue();
            assertThat(btc.baseAsset()).isEqualTo("BTC");
            assertThat(btc.quoteAsset()).isEqualTo("USDT");
            assertThat(btc.contractType()).isNull();
            assertThat(btc.tickSize()).isEqualTo(new BigDecimal("0.01000000"));
            assertThat(btc.stepSize()).isEqualTo(new BigDecimal("0.00001000"));
            assertThat(btc.minQuantity()).isEqualTo(new BigDecimal("0.00001000"));
            assertThat(btc.minNotional()).isEqualTo(new BigDecimal("5.00000000"));
            ExchangeSymbol old = symbols.get(1);
            assertThat(old.isTrading())
                    .as("NSF-01 flags a pair that stops trading")
                    .isFalse();
            assertThat(old.tickSize())
                    .as("an absent filter is null, never zero")
                    .isNull();
        }

        /** NSF-01 on futures: the contract type, and MIN_NOTIONAL spelled with {@code notional}. */
        @Test
        void NSF01_futuresExchangeInfo_readsContractTypeAndItsMinNotional() {
            exchange.on(FUTURES_EXCHANGE_INFO, Answer.ok("""
                    {"symbols":[{"symbol":"BTCUSDT","status":"TRADING","contractType":"PERPETUAL",
                      "baseAsset":"BTC","quoteAsset":"USDT","filters":[
                        {"filterType":"PRICE_FILTER","tickSize":"0.10"},
                        {"filterType":"LOT_SIZE","stepSize":"0.001","minQty":"0.001"},
                        {"filterType":"MIN_NOTIONAL","notional":"100"}]}]}"""));

            ExchangeSymbol btc = client.futuresExchangeInfo().get(0);

            assertThat(btc.contractType()).isEqualTo("PERPETUAL");
            assertThat(btc.tickSize()).isEqualTo(new BigDecimal("0.10"));
            assertThat(btc.minNotional()).isEqualTo(new BigDecimal("100"));
        }

        /** Spot MIN_NOTIONAL, the older spelling of the Spot filter, is read too. */
        @Test
        void NSF01_theOlderSpotMinNotionalFilter_isReadToo() {
            exchange.on(SPOT_EXCHANGE_INFO, Answer.ok("""
                    {"symbols":[{"symbol":"ETHUSDT","status":"TRADING","baseAsset":"ETH","quoteAsset":"USDT",
                      "filters":[{"filterType":"MIN_NOTIONAL","minNotional":"10.00000000"}]}]}"""));

            assertThat(client.spotExchangeInfo().get(0).minNotional()).isEqualTo(new BigDecimal("10.00000000"));
        }

        /** NSF-02: the documented field order, the query the exchange needs, and the Spot path. */
        @Test
        void NSF02_spotKlines_readTheFieldOrderAndSendTheRange() {
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY));
            Instant start = Instant.parse("2026-09-24T09:00:00Z");
            Instant end = Instant.parse("2026-09-24T10:00:00Z");

            Kline kline = client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, start, end, 1000)
                    .get(0);

            assertThat(kline.openTime()).isEqualTo(Instant.ofEpochMilli(1727172000000L));
            assertThat(kline.closeTime()).isEqualTo(Instant.ofEpochMilli(1727172059999L));
            assertThat(kline.open()).isEqualTo(new BigDecimal("63012.01000000"));
            assertThat(kline.high()).isEqualTo(new BigDecimal("63100.00000000"));
            assertThat(kline.low()).isEqualTo(new BigDecimal("62950.10000000"));
            assertThat(kline.close()).isEqualTo(new BigDecimal("63050.55000000"));
            assertThat(kline.volume()).isEqualTo(new BigDecimal("12.00000001"));
            assertThat(kline.quoteVolume()).isEqualTo(new BigDecimal("756600.12345678"));
            assertThat(kline.tradeCount()).isEqualTo(1234);
            assertThat(exchange.requests().get(0).getQuery())
                    .isEqualTo("symbol=BTCUSDT&interval=1m&startTime=" + start.toEpochMilli() + "&endTime="
                            + end.toEpochMilli() + "&limit=1000");
        }

        /** The futures candles come from the futures host and path, and an absent range is not sent. */
        @Test
        void NSF02_futuresKlines_useTheFuturesPathAndOmitAnAbsentRange() {
            exchange.on(FUTURES_KLINES, Answer.ok(KLINES_BODY));

            client.klines(BinanceVenue.USD_M_FUTURES, "BTCUSDT", MarketInterval.FOUR_HOURS, null, null, 1500);

            assertThat(exchange.hits(FUTURES_KLINES)).isOne();
            assertThat(exchange.requests().get(0).getQuery()).isEqualTo("symbol=BTCUSDT&interval=4h&limit=1500");
        }

        /**
         * ADR-008: a decimal keeps every digit the exchange sent — the smallest step, and more significant
         * digits than a double can hold — because it is parsed from the string and never through a double.
         */
        @Test
        void TD54_decimals_keepEveryDigitTheExchangeSent() {
            exchange.on(SPOT_KLINES, Answer.ok("""
                    [[1727172000000,"0.00000001","1234567890123456.123456789012","0.00000001","0.10000000",
                      "0.00000001",1727172059999,"0",1,"0","0","0"]]"""));

            Kline kline = client.klines(BinanceVenue.SPOT, "SHIBUSDT", MarketInterval.ONE_MINUTE, null, null, 1)
                    .get(0);

            assertThat(kline.open()).isEqualTo(new BigDecimal("0.00000001"));
            assertThat(kline.open().scale()).isEqualTo(8);
            assertThat(kline.high().toPlainString()).isEqualTo("1234567890123456.123456789012");
            assertThat(kline.close())
                    .as("trailing zeros are kept, so the scale is the exchange's")
                    .isEqualTo(new BigDecimal("0.10000000"));
        }

        /** BR-11: the next funding time is read from the source, with the mark and index prices. */
        @Test
        void BR11_premiumIndex_readsTheNextFundingTimeFromTheSource() {
            exchange.on("/fapi/v1/premiumIndex", Answer.ok("""
                    {"symbol":"BTCUSDT","markPrice":"63055.12345678","indexPrice":"63050.00000000",
                     "estimatedSettlePrice":"63040.1","lastFundingRate":"0.00010000","interestRate":"0.0001",
                     "nextFundingTime":1727179200000,"time":1727172030000}"""));

            PremiumIndex index = client.premiumIndex("BTCUSDT");

            assertThat(index.markPrice()).isEqualTo(new BigDecimal("63055.12345678"));
            assertThat(index.indexPrice()).isEqualTo(new BigDecimal("63050.00000000"));
            assertThat(index.lastFundingRate()).isEqualTo(new BigDecimal("0.00010000"));
            assertThat(index.nextFundingTime()).isEqualTo(Instant.ofEpochMilli(1727179200000L));
            assertThat(index.time()).isEqualTo(Instant.ofEpochMilli(1727172030000L));
            assertThat(exchange.requests().get(0).getQuery()).isEqualTo("symbol=BTCUSDT");
        }

        /** NSF-04: settled funding rates; a record without a mark price reads it as absent. */
        @Test
        void NSF04_fundingRates_readSettledRatesAndAnAbsentMarkPrice() {
            exchange.on("/fapi/v1/fundingRate", Answer.ok("""
                    [{"symbol":"BTCUSDT","fundingTime":1727164800000,"fundingRate":"-0.00001234","markPrice":"63000.10"},
                     {"symbol":"BTCUSDT","fundingTime":1727193600000,"fundingRate":"0.00010000","markPrice":""}]"""));

            List<FundingRate> rates = client.fundingRates("BTCUSDT", Instant.ofEpochMilli(1727164800000L), null, 100);

            assertThat(rates.get(0).fundingRate()).isEqualTo(new BigDecimal("-0.00001234"));
            assertThat(rates.get(0).markPrice()).isEqualTo(new BigDecimal("63000.10"));
            assertThat(rates.get(0).fundingTime()).isEqualTo(Instant.ofEpochMilli(1727164800000L));
            assertThat(rates.get(1).markPrice()).isNull();
            assertThat(exchange.requests().get(0).getQuery())
                    .isEqualTo("symbol=BTCUSDT&startTime=1727164800000&limit=100");
        }

        /** NSF-04 and BR-10: the present open interest. */
        @Test
        void NSF04_openInterest_readsThePresentValue() {
            exchange.on("/fapi/v1/openInterest", Answer.ok("""
                    {"openInterest":"10659.509","symbol":"BTCUSDT","time":1727172030000}"""));

            OpenInterest interest = client.openInterest("BTCUSDT");

            assertThat(interest.openInterest()).isEqualTo(new BigDecimal("10659.509"));
            assertThat(interest.time()).isEqualTo(Instant.ofEpochMilli(1727172030000L));
        }

        /** NSF-04: the long/short account ratio per period, with the period as the exchange spells it. */
        @Test
        void NSF04_longShortAccountRatios_readEachPeriod() {
            exchange.on("/futures/data/globalLongShortAccountRatio", Answer.ok("""
                    [{"symbol":"BTCUSDT","longShortRatio":"1.8105","longAccount":"0.6442","shortAccount":"0.3558",
                      "timestamp":1727171700000}]"""));

            LongShortRatio ratio = client.longShortAccountRatios("BTCUSDT", MarketInterval.FIVE_MINUTES, null, null, 30)
                    .get(0);

            assertThat(ratio.longShortRatio()).isEqualTo(new BigDecimal("1.8105"));
            assertThat(ratio.longAccount()).isEqualTo(new BigDecimal("0.6442"));
            assertThat(ratio.shortAccount()).isEqualTo(new BigDecimal("0.3558"));
            assertThat(ratio.timestamp()).isEqualTo(Instant.ofEpochMilli(1727171700000L));
            assertThat(exchange.requests().get(0).getQuery()).isEqualTo("symbol=BTCUSDT&period=5m&limit=30");
        }
    }

    // ------------------------------------------------------------------ the weight budget

    @Nested
    class RespectsTheRequestWeight {

        /**
         * TECHNICAL_DESIGN 7.1 step 5: at 80% of the budget the client stops calling for the rest of the
         * minute, before the exchange has to say 429 — and the refusal says when to come back.
         */
        @Test
        void NSF02_atEightyPercentOfTheBudget_theVenueIsPausedUntilTheNextMinute() {
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY).withHeader("X-MBX-USED-WEIGHT-1M", "4800"));

            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);

            BinanceClientException paused = refusalOf(
                    () -> client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1));
            assertThat(paused.kind()).isEqualTo(BinanceClientException.Kind.RATE_LIMITED);
            assertThat(paused.retryAt()).contains(Instant.parse("2026-09-24T10:01:00Z"));
            assertThat(exchange.hits(SPOT_KLINES))
                    .as("the refused call never left the process")
                    .isOne();

            clock.set(Instant.parse("2026-09-24T10:01:00Z"));
            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(2);
        }

        /** Just below the threshold nothing pauses. */
        @Test
        void NSF02_justBelowTheThreshold_callsContinue() {
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY).withHeader("X-MBX-USED-WEIGHT-1M", "4799"));

            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);

            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(2);
        }

        /** Spot and futures have separate budgets per IP: a paused Spot does not stop futures. */
        @Test
        void TD711_eachVenueHasItsOwnBudget() {
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY).withHeader("X-MBX-USED-WEIGHT-1M", "6000"));
            exchange.on(FUTURES_KLINES, Answer.ok(KLINES_BODY).withHeader("X-MBX-USED-WEIGHT-1M", "100"));
            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);

            client.klines(BinanceVenue.USD_M_FUTURES, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);

            assertThat(exchange.hits(FUTURES_KLINES)).isOne();
        }

        /** The futures budget is its own, smaller one: 80% of 2400. */
        @Test
        void TD711_theFuturesBudget_pausesAtEightyPercentOfItsOwnLimit() {
            exchange.on(FUTURES_KLINES, Answer.ok(KLINES_BODY).withHeader("X-MBX-USED-WEIGHT-1M", "1920"));
            client.klines(BinanceVenue.USD_M_FUTURES, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);

            assertThat(refusalOf(() -> client.premiumIndex("BTCUSDT")).kind())
                    .isEqualTo(BinanceClientException.Kind.RATE_LIMITED);
        }

        /** A header that is not a number reports nothing, and the call is unaffected. */
        @Test
        void TD712_anUnreadableWeightHeader_isIgnored() {
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY).withHeader("X-MBX-USED-WEIGHT-1M", "lots"));

            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);

            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ 429 and 418

    @Nested
    class WaitsOutRateLimitsAndBans {

        /** A 429 is not retried: every call waits for the exchange's Retry-After, then resumes. */
        @Test
        void BR09_a429_isWaitedOutForItsRetryAfterAndNeverRetried() {
            exchange.on(SPOT_KLINES, Answer.status(429).withHeader("Retry-After", "7"), Answer.ok(KLINES_BODY));

            BinanceClientException limited = refusalOf(
                    () -> client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1));
            assertThat(limited.kind()).isEqualTo(BinanceClientException.Kind.RATE_LIMITED);
            assertThat(limited.retryAt()).contains(NOW.plusSeconds(7));
            assertThat(exchange.hits(SPOT_KLINES)).as("a 429 is never retried").isOne();

            clock.advance(Duration.ofSeconds(7).minusMillis(1));
            assertThat(refusalOf(() -> client.klines(
                                    BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                            .kind())
                    .isEqualTo(BinanceClientException.Kind.RATE_LIMITED);
            assertThat(exchange.hits(SPOT_KLINES)).isOne();

            clock.advance(Duration.ofMillis(1));
            assertThat(client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                    .hasSize(1);
        }

        /** A 429 without Retry-After waits the configured default instead. */
        @Test
        void BR09_a429WithoutRetryAfter_waitsTheConfiguredDefault() {
            exchange.on(SPOT_KLINES, Answer.status(429));

            BinanceClientException limited = refusalOf(
                    () -> client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1));

            assertThat(limited.retryAt()).contains(NOW.plus(Duration.ofMinutes(2)));
        }

        /** A Retry-After that is not a number of seconds falls back to the default too. */
        @Test
        void BR09_anUnreadableRetryAfter_waitsTheConfiguredDefault() {
            exchange.on(SPOT_KLINES, Answer.status(429).withHeader("Retry-After", "soon"));

            assertThat(refusalOf(() -> client.klines(
                                    BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                            .retryAt())
                    .contains(NOW.plus(Duration.ofMinutes(2)));
        }

        /**
         * A 418 is a ban for having ignored 429s. Every call to that venue stops until the ban ends, none
         * is retried, and the other venue is unaffected.
         */
        @Test
        void BR09_a418_stopsEveryCallToThatVenueUntilTheBanEnds() {
            exchange.on(SPOT_EXCHANGE_INFO, Answer.status(418).withHeader("Retry-After", "120"));
            exchange.on(FUTURES_KLINES, Answer.ok(KLINES_BODY));

            BinanceClientException banned = refusalOf(() -> client.spotExchangeInfo());
            assertThat(banned.kind()).isEqualTo(BinanceClientException.Kind.BANNED);
            assertThat(banned.venue()).isEqualTo(BinanceVenue.SPOT);
            assertThat(banned.retryAt()).contains(NOW.plusSeconds(120));

            assertThat(refusalOf(() -> client.klines(
                                    BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                            .kind())
                    .isEqualTo(BinanceClientException.Kind.BANNED);
            assertThat(exchange.hits(SPOT_EXCHANGE_INFO)).isOne();
            assertThat(exchange.hits(SPOT_KLINES))
                    .as("stopped before leaving the process")
                    .isZero();
            client.klines(BinanceVenue.USD_M_FUTURES, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
        }
    }

    // ------------------------------------------------------------------ transient failures

    @Nested
    class RetriesTransientFailuresAndBreaksTheCircuit {

        /** A 5xx is retried: two failures and a success is a success, after three requests. */
        @Test
        void NSF02_a5xx_isRetriedUntilItSucceeds() {
            exchange.on(SPOT_KLINES, Answer.status(502), Answer.status(503), Answer.ok(KLINES_BODY));

            assertThat(client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                    .hasSize(1);
            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(3);
        }

        /** A 5xx that outlasts the retries is UNAVAILABLE, after exactly 1 + maxRetries requests. */
        @Test
        void NSF02_a5xxThatOutlastsTheRetries_isUnavailable() {
            exchange.on(SPOT_KLINES, Answer.status(500));

            BinanceClientException down = refusalOf(
                    () -> client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1));

            assertThat(down.kind()).isEqualTo(BinanceClientException.Kind.UNAVAILABLE);
            assertThat(down.retryAt()).isEmpty();
            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(3);
        }

        /** A response slower than the read timeout is a timeout, retried like a 5xx and then UNAVAILABLE. */
        @Test
        void NSF02_aResponseSlowerThanTheReadTimeout_timesOut() throws Exception {
            client.close();
            client = clientFor(exchange.baseUrl(), exchange.baseUrl(), Duration.ofMillis(100));
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY).after(Duration.ofMillis(600)));

            BinanceClientException slow = refusalOf(
                    () -> client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1));

            assertThat(slow.kind()).isEqualTo(BinanceClientException.Kind.UNAVAILABLE);
            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(3);
        }

        /** A host that refuses the connection is UNAVAILABLE, not an unhandled error. */
        @Test
        void NSF02_aRefusedConnection_isUnavailable() {
            URI nowhere = URI.create("http://127.0.0.1:1");
            client.close();
            client = clientFor(nowhere, nowhere, Duration.ofMillis(200));

            assertThat(refusalOf(() -> client.spotExchangeInfo()).kind())
                    .isEqualTo(BinanceClientException.Kind.UNAVAILABLE);
        }

        /**
         * The circuit breaker: two failed calls in a row open it; while open nothing reaches the
         * exchange; after the open time one trial call is let through, and its success closes it.
         */
        @Test
        void TD712_repeatedFailures_openTheCircuitAndASuccessfulTrialClosesIt() {
            exchange.on(
                    SPOT_KLINES,
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.ok(KLINES_BODY));
            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);
            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);

            BinanceClientException open = callKlinesExpecting(BinanceClientException.Kind.CIRCUIT_OPEN);
            assertThat(open.retryAt()).contains(NOW.plusSeconds(30));
            assertThat(exchange.hits(SPOT_KLINES))
                    .as("an open circuit sends nothing")
                    .isEqualTo(6);

            clock.advance(Duration.ofSeconds(30));
            assertThat(client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                    .as("the trial call")
                    .hasSize(1);
            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
            assertThat(exchange.hits(SPOT_KLINES)).isEqualTo(8);
        }

        /** A failed trial opens the circuit again at once, without waiting for another run of failures. */
        @Test
        void TD712_aFailedTrial_opensTheCircuitAgain() {
            exchange.on(SPOT_KLINES, Answer.status(500));
            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);
            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);
            clock.advance(Duration.ofSeconds(30));

            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);

            assertThat(callKlinesExpecting(BinanceClientException.Kind.CIRCUIT_OPEN)
                            .retryAt())
                    .contains(NOW.plusSeconds(60));
        }

        /** A success in between ends the run: failures must be consecutive to open the circuit. */
        @Test
        void TD712_aSuccessBetweenFailures_endsTheRun() {
            exchange.on(
                    SPOT_KLINES,
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.ok(KLINES_BODY),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.ok(KLINES_BODY));
            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);
            client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
            callKlinesExpecting(BinanceClientException.Kind.UNAVAILABLE);

            assertThat(client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                    .hasSize(1);
        }

        private BinanceClientException callKlinesExpecting(BinanceClientException.Kind kind) {
            BinanceClientException refusal = refusalOf(
                    () -> client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1));
            assertThat(refusal.kind()).isEqualTo(kind);
            return refusal;
        }
    }

    // ------------------------------------------------------------------ requests that will not improve

    @Nested
    class ReportsWhatWaitingWillNotFix {

        /** A 4xx other than 418 and 429 is the request's fault: REJECTED, once, and no circuit counts it. */
        @Test
        void BR09_aBadRequest_isRejectedOnceAndDoesNotOpenTheCircuit() {
            exchange.on(SPOT_KLINES, Answer.status(400));

            for (int call = 0; call < 3; call++) {
                assertThat(refusalOf(() -> client.klines(
                                        BinanceVenue.SPOT, "NOPE", MarketInterval.ONE_MINUTE, null, null, 1))
                                .kind())
                        .isEqualTo(BinanceClientException.Kind.REJECTED);
            }

            assertThat(exchange.hits(SPOT_KLINES))
                    .as("each call once, none retried, none refused by a breaker")
                    .isEqualTo(3);
        }

        /** A body that is not the documented shape is MALFORMED, whatever is wrong with it. */
        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "not json at all",
                    "{\"symbols\":\"not an array\"}",
                    "{\"no symbols\":[]}",
                    "{\"symbols\":[{\"symbol\":\"BTCUSDT\"}]}",
                    "{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"BTC\","
                            + "\"quoteAsset\":\"USDT\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"tickSize\":0.01}]}]}"
                })
        void TD712_aMalformedBody_isMalformed(String body) {
            exchange.on(SPOT_EXCHANGE_INFO, Answer.ok(body));

            BinanceClientException malformed = refusalOf(() -> client.spotExchangeInfo());

            assertThat(malformed.kind()).isEqualTo(BinanceClientException.Kind.MALFORMED);
            assertThat(exchange.hits(SPOT_EXCHANGE_INFO)).as("never retried").isOne();
        }

        /**
         * ADR-008: a price sent as a JSON number instead of a string is refused rather than read through a
         * double, and so is a time that is not epoch milliseconds.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "[[1727172000000,63012.01,\"1\",\"1\",\"1\",\"1\",1727172059999,\"1\",1,\"0\",\"0\",\"0\"]]",
                    "[[\"1727172000000\",\"1\",\"1\",\"1\",\"1\",\"1\",1727172059999,\"1\",1,\"0\",\"0\",\"0\"]]",
                    "[[1727172000000,\"1\",\"1\"]]",
                    "{\"not\":\"an array\"}"
                })
        void TD54_aKlineThatIsNotTheDocumentedShape_isMalformed(String body) {
            exchange.on(FUTURES_KLINES, Answer.ok(body));

            assertThat(refusalOf(() -> client.klines(
                                    BinanceVenue.USD_M_FUTURES, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                            .kind())
                    .isEqualTo(BinanceClientException.Kind.MALFORMED);
        }

        /**
         * The string-only rule is for decimals only. The fields the documentation sends as numbers — times,
         * the trade count — are accepted as numbers, and numeric fields the client does not read (precisions,
         * order limits, booleans) are ignored whatever their type.
         */
        @Test
        void TD54_documentedNumericFields_areAcceptedAsNumbers() {
            exchange.on(SPOT_EXCHANGE_INFO, Answer.ok("""
                {"serverTime":1727172030000,"symbols":[{"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC",
                  "baseAssetPrecision":8,"quoteAsset":"USDT","quotePrecision":8,"isSpotTradingAllowed":true,
                  "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.01"},
                             {"filterType":"MAX_NUM_ORDERS","maxNumOrders":200}]}]}"""));
            exchange.on(SPOT_KLINES, Answer.ok(KLINES_BODY));

            assertThat(client.spotExchangeInfo().get(0).tickSize()).isEqualTo(new BigDecimal("0.01"));
            assertThat(client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1)
                            .get(0)
                            .tradeCount())
                    .isEqualTo(1234);
        }

        /** A documented integer that is not one — a trade count with a fraction, or sent as text — is MALFORMED. */
        @ParameterizedTest(name = "trade count {0}")
        @ValueSource(strings = {"12.5", "\"1234\""})
        void TD54_aTradeCountThatIsNotAnInteger_isMalformed(String tradeCount) {
            exchange.on(
                    SPOT_KLINES,
                    Answer.ok("[[1727172000000,\"1\",\"1\",\"1\",\"1\",\"1\",1727172059999,\"1\"," + tradeCount
                            + ",\"0\",\"0\",\"0\"]]"));

            assertThat(refusalOf(() -> client.klines(
                                    BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                            .kind())
                    .isEqualTo(BinanceClientException.Kind.MALFORMED);
        }

        /**
         * MALFORMED is not an outage: however many unreadable bodies arrive, the circuit never opens,
         * because the exchange answered each time. Only 5xx, timeouts and refused connections count.
         */
        @Test
        void TD712_malformedBodies_neverOpenTheCircuit() {
            exchange.on(SPOT_EXCHANGE_INFO, Answer.ok("not json"));

            for (int call = 0; call < 5; call++) {
                assertThat(refusalOf(() -> client.spotExchangeInfo()).kind())
                        .isEqualTo(BinanceClientException.Kind.MALFORMED);
            }

            assertThat(exchange.hits(SPOT_EXCHANGE_INFO))
                    .as("every call reached the exchange")
                    .isEqualTo(5);
        }

        /**
         * An answered request — even a malformed or rejected one — proves the host is up, so it ends a run of
         * failures: failure, malformed, failure is two runs of one, and the circuit (threshold two) stays shut.
         */
        @Test
        void TD712_anAnsweredRequest_endsARunOfFailures() {
            exchange.on(
                    SPOT_KLINES,
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.ok("not json"),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.status(500),
                    Answer.ok(KLINES_BODY));

            for (BinanceClientException.Kind expected : new BinanceClientException.Kind[] {
                BinanceClientException.Kind.UNAVAILABLE,
                BinanceClientException.Kind.MALFORMED,
                BinanceClientException.Kind.UNAVAILABLE
            }) {
                assertThat(refusalOf(() -> client.klines(
                                        BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                                .kind())
                        .isEqualTo(expected);
            }

            assertThat(client.klines(BinanceVenue.SPOT, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                    .as("not CIRCUIT_OPEN")
                    .hasSize(1);
        }

        /** Arguments the exchange would refuse are refused before a request is made. */
        @Test
        void TD712_invalidArguments_areRefusedBeforeAnyRequest() {
            assertThatThrownBy(() -> client.premiumIndex(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.openInterest(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.fundingRates("BTCUSDT", null, null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.klines(null, "BTCUSDT", MarketInterval.ONE_DAY, null, null, 1))
                    .isInstanceOf(NullPointerException.class);
            assertThat(exchange.requests()).isEmpty();
        }
    }

    /** BR-08: a candle is closed only once its last millisecond has passed. */
    @Test
    void BR08_aCandle_isClosedOnlyAfterItsCloseTime() {
        Kline kline = new Kline(
                Instant.parse("2026-09-24T10:00:00Z"),
                Instant.parse("2026-09-24T10:00:59.999Z"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                1);

        assertThat(kline.isClosedAt(Instant.parse("2026-09-24T10:00:59.999Z"))).isFalse();
        assertThat(kline.isClosedAt(Instant.parse("2026-09-24T10:01:00Z"))).isTrue();
    }

    private BinanceRestClient clientFor(URI spot, URI futures, Duration readTimeout) {
        return new BinanceRestClient(
                new BinanceClientProperties(
                        new BinanceClientProperties.Venue(spot, 6000),
                        new BinanceClientProperties.Venue(futures, 2400),
                        Duration.ofMillis(500),
                        readTimeout,
                        80,
                        Duration.ofMinutes(2),
                        new BinanceClientProperties.Retry(
                                2, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO),
                        new BinanceClientProperties.CircuitBreaker(2, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                bans);
    }

    private static BinanceClientException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (BinanceClientException refusal) {
            return refusal;
        }
        throw new AssertionError("the call was expected to be refused");
    }
}
