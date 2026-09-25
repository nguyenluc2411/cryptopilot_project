package com.cryptopilot.market.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way into the Binance public market data REST API, for Spot and USDⓈ-M futures.
 *
 * <h2>What it offers, and what it never will</h2>
 *
 * <p>Exactly the endpoints the ingestion tasks need — exchange information and candles on both venues
 * (NSF-01, NSF-02), and the mark price, settled funding rates, open interest and long/short account
 * ratio of futures (NSF-04). Every one is public: there is no API key, no signature, and no account or
 * order endpoint anywhere in this class, because BR-09 forbids the system to hold one.
 *
 * <p>It is an anti-corruption layer. The exchange's JSON is read by {@link BinanceResponseParser} and
 * never leaves this package; callers receive the client's own records, with every price and quantity a
 * {@code BigDecimal} parsed from the exchange's string.
 *
 * <h2>How it behaves when the exchange does not answer well</h2>
 *
 * <p>See TECHNICAL_DESIGN 7.1.2. In short, per venue: a timeout on every call; the used request weight
 * read from every response, with calls refused for the rest of the minute once it reaches the pause
 * share of the budget; a 429 or a 418 refuses every call until the exchange's {@code Retry-After}, the
 * 418 logged as an alert; a 5xx, a timeout or a refused connection retried with exponential back-off
 * and jitter, then counted towards a circuit breaker; any other 4xx or an unreadable body reported at
 * once and never retried. Nothing sleeps for a rate limit: a refusal carries the instant to try again
 * and the job that called decides.
 *
 * <p>Rule: BR-09, BR-10, BR-11; NSF-01, NSF-02, NSF-04; TECHNICAL_DESIGN 7.1, 7.1.1 and 7.1.2.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14
 * ("Anticorruption Layer").
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5
 * ("Timeouts": never wait on an integration point without a bound; "Circuit Breaker").
 */
public class BinanceRestClient implements AutoCloseable {

    /** The header in which the exchange reports the weight this IP used in the current minute. */
    static final String USED_WEIGHT_HEADER = "X-MBX-USED-WEIGHT-1M";

    private final HttpClient httpClient;
    private final Map<BinanceVenue, RestClient> venues = new EnumMap<>(BinanceVenue.class);
    private final Map<BinanceVenue, BinanceRequestGate> gates = new EnumMap<>(BinanceVenue.class);
    private final RetryTemplate retry;
    private final BinanceResponseParser parser;
    private final Duration defaultRetryAfter;

    public BinanceRestClient(BinanceClientProperties properties, Clock clock, JsonMapper json, BinanceBanStore bans) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        register(BinanceVenue.SPOT, properties.spot(), properties, requestFactory, clock, bans);
        register(BinanceVenue.USD_M_FUTURES, properties.futures(), properties, requestFactory, clock, bans);

        BinanceClientProperties.Retry retryProperties = properties.retry();
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(retryProperties.maxRetries())
                .delay(retryProperties.initialDelay())
                .multiplier(retryProperties.multiplier())
                .maxDelay(retryProperties.maxDelay())
                .jitter(retryProperties.jitter())
                .predicate(BinanceRestClient::isTransient)
                .build());
        this.parser = new BinanceResponseParser(json);
        this.defaultRetryAfter = properties.defaultRetryAfter();
    }

    // ------------------------------------------------------------------ both venues

    /** Every symbol of the Spot market with its status and filters (NSF-01; weight 20). */
    public List<ExchangeSymbol> spotExchangeInfo() {
        return parser.exchangeSymbols(BinanceVenue.SPOT, get(BinanceVenue.SPOT, "/api/v3/exchangeInfo", Map.of()));
    }

    /** Every symbol of the USDⓈ-M futures market with its status, contract type and filters (NSF-01). */
    public List<ExchangeSymbol> futuresExchangeInfo() {
        return parser.exchangeSymbols(
                BinanceVenue.USD_M_FUTURES, get(BinanceVenue.USD_M_FUTURES, "/fapi/v1/exchangeInfo", Map.of()));
    }

    /**
     * Candles of one symbol, oldest first (NSF-02). The last one may still be forming; see
     * {@link Kline#isClosedAt}.
     *
     * @param startTime the first open time wanted, or {@code null} for the exchange's default
     * @param endTime the last open time wanted, or {@code null} for the exchange's default
     * @param limit how many candles, up to the exchange's maximum (Spot 1000, futures 1500)
     */
    public List<Kline> klines(
            BinanceVenue venue, String symbol, MarketInterval interval, Instant startTime, Instant endTime, int limit) {
        Objects.requireNonNull(venue, "venue must not be null");
        Map<String, Object> query = symbolQuery(symbol);
        query.put(
                "interval",
                Objects.requireNonNull(interval, "interval must not be null").code());
        putRange(query, startTime, endTime, limit);
        String path = venue == BinanceVenue.SPOT ? "/api/v3/klines" : "/fapi/v1/klines";
        return parser.klines(venue, get(venue, path, query));
    }

    // ------------------------------------------------------------------ futures only

    /** The mark price, the predicted funding rate and the next funding time of one symbol (BR-11). */
    public PremiumIndex premiumIndex(String symbol) {
        return parser.premiumIndex(get(BinanceVenue.USD_M_FUTURES, "/fapi/v1/premiumIndex", symbolQuery(symbol)));
    }

    /**
     * Settled funding rates of one symbol (NSF-04, TECHNICAL_DESIGN 7.1 step 8).
     *
     * @param startTime the first settlement wanted, or {@code null}
     * @param endTime the last settlement wanted, or {@code null}
     */
    public List<FundingRate> fundingRates(String symbol, Instant startTime, Instant endTime, int limit) {
        Map<String, Object> query = symbolQuery(symbol);
        putRange(query, startTime, endTime, limit);
        return parser.fundingRates(get(BinanceVenue.USD_M_FUTURES, "/fapi/v1/fundingRate", query));
    }

    /** The present open interest of one symbol (NSF-04; BR-10: history is the system's own). */
    public OpenInterest openInterest(String symbol) {
        return parser.openInterest(get(BinanceVenue.USD_M_FUTURES, "/fapi/v1/openInterest", symbolQuery(symbol)));
    }

    /**
     * The open interest and its value of one symbol, one entry per period, oldest first (NSF-04). The exchange
     * keeps the latest month only (BR-10). With only {@code startTime} the exchange answers the most recent
     * entries, not those after it, so a caller paging forward sends both ends of the range.
     */
    public List<OpenInterestStatistic> openInterestStatistics(
            String symbol, MarketInterval period, Instant startTime, Instant endTime, int limit) {
        Map<String, Object> query = symbolQuery(symbol);
        query.put(
                "period",
                Objects.requireNonNull(period, "period must not be null").code());
        putRange(query, startTime, endTime, limit);
        return parser.openInterestStatistics(get(BinanceVenue.USD_M_FUTURES, "/futures/data/openInterestHist", query));
    }

    /**
     * The funding settings of every symbol whose cap, floor or interval the exchange has adjusted (BR-11). A
     * symbol missing from the answer has no interval stated by this source; see {@link FundingInfo}.
     */
    public List<FundingInfo> fundingInfo() {
        return parser.fundingInfo(get(BinanceVenue.USD_M_FUTURES, "/fapi/v1/fundingInfo", Map.of()));
    }

    /**
     * The global long/short account ratio of one symbol, one entry per period (NSF-04). The exchange
     * keeps the latest 30 days only (BR-10).
     */
    public List<LongShortRatio> longShortAccountRatios(
            String symbol, MarketInterval period, Instant startTime, Instant endTime, int limit) {
        Map<String, Object> query = symbolQuery(symbol);
        query.put(
                "period",
                Objects.requireNonNull(period, "period must not be null").code());
        putRange(query, startTime, endTime, limit);
        return parser.longShortRatios(
                get(BinanceVenue.USD_M_FUTURES, "/futures/data/globalLongShortAccountRatio", query));
    }

    /**
     * The request weight this IP has used on a venue in the current minute, as the exchange last reported
     * it; zero before the first response of the minute. The budget is shared by every caller of this client,
     * so a caller that must leave room for others — the candle backfill (T-020) — paces itself on this.
     */
    public int usedWeightThisMinute(BinanceVenue venue) {
        return gates.get(Objects.requireNonNull(venue, "venue must not be null"))
                .usedWeightThisMinute();
    }

    /** The documented weight budget per minute of a venue (TECHNICAL_DESIGN 7.1.1). */
    public int weightPerMinute(BinanceVenue venue) {
        return gates.get(Objects.requireNonNull(venue, "venue must not be null"))
                .requestWeightPerMinute();
    }

    /** Releases the HTTP client's connections when the application stops. */
    @Override
    public void close() {
        httpClient.close();
    }

    // ------------------------------------------------------------------ the call

    /**
     * One GET through the gate of its venue, with retries of transient failures. Answers the body of a
     * 2xx and throws {@link BinanceClientException} for everything else.
     */
    private String get(BinanceVenue venue, String path, Map<String, Object> query) {
        BinanceRequestGate gate = gates.get(venue);
        gate.admit();
        try {
            String body = retry.execute(() -> call(venue, path, query));
            gate.succeeded();
            return body;
        } catch (RetryException exhausted) {
            Throwable last = exhausted.getCause();
            if (last instanceof BinanceClientException refusal) {
                throw refusal;
            }
            gate.failed();
            throw new BinanceClientException(
                    BinanceClientException.Kind.UNAVAILABLE,
                    venue,
                    venue + " " + path + " failed after " + (exhausted.getRetryCount() + 1) + " attempts",
                    null,
                    last);
        }
    }

    /**
     * One attempt. A 2xx answers its body; a 429, a 418 or another 4xx is a {@link BinanceClientException}
     * that the retry lets through untouched; a 5xx is a {@link TransientFailure}, which it retries, as it
     * does a {@link ResourceAccessException} — the timeout or connection failure the HTTP layer raises.
     */
    private String call(BinanceVenue venue, String path, Map<String, Object> query) {
        BinanceRequestGate gate = gates.get(venue);
        return venues.get(venue)
                .get()
                .uri(builder -> {
                    builder.path(path);
                    query.forEach(builder::queryParam);
                    return builder.build();
                })
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    recordUsedWeight(gate, response.getHeaders());
                    if (status >= 200 && status < 300) {
                        return body;
                    }
                    if (status >= 500) {
                        throw new TransientFailure(venue + " " + path + " answered " + status);
                    }
                    gate.succeeded();
                    if (status == 429) {
                        Instant until = gate.rateLimited(retryAfter(response.getHeaders()));
                        throw refusal(BinanceClientException.Kind.RATE_LIMITED, venue, path, status, until);
                    }
                    if (status == 418) {
                        Instant until = gate.banned(retryAfter(response.getHeaders()), "HTTP 418 from " + path);
                        throw refusal(BinanceClientException.Kind.BANNED, venue, path, status, until);
                    }
                    throw refusal(BinanceClientException.Kind.REJECTED, venue, path, status, null);
                });
    }

    private void register(
            BinanceVenue venue,
            BinanceClientProperties.Venue venueProperties,
            BinanceClientProperties properties,
            JdkClientHttpRequestFactory requestFactory,
            Clock clock,
            BinanceBanStore bans) {
        venues.put(
                venue,
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .baseUrl(venueProperties.baseUrl().toString())
                        .build());
        gates.put(
                venue,
                new BinanceRequestGate(
                        venue,
                        venueProperties.requestWeightPerMinute(),
                        properties.weightPausePercent(),
                        properties.circuitBreaker().failureThreshold(),
                        properties.circuitBreaker().openDuration(),
                        clock,
                        bans));
    }

    private static void recordUsedWeight(BinanceRequestGate gate, HttpHeaders headers) {
        String used = headers.getFirst(USED_WEIGHT_HEADER);
        if (used == null) {
            return;
        }
        try {
            gate.recordUsedWeight(Integer.parseInt(used.trim()));
        } catch (NumberFormatException unreadable) {
            // A header that is not a number reports nothing; the budget is then enforced by the 429 path.
        }
    }

    /** The exchange's {@code Retry-After}, in seconds, or the configured default when it sent none. */
    private Duration retryAfter(HttpHeaders headers) {
        String seconds = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (seconds != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(seconds.trim()));
            } catch (NumberFormatException unreadable) {
                // Fall through to the default.
            }
        }
        return defaultRetryAfter;
    }

    private static BinanceClientException refusal(
            BinanceClientException.Kind kind, BinanceVenue venue, String path, int status, Instant retryAt) {
        return new BinanceClientException(kind, venue, venue + " " + path + " answered " + status, retryAt, null);
    }

    private static boolean isTransient(Throwable failure) {
        return failure instanceof TransientFailure || failure instanceof ResourceAccessException;
    }

    private static Map<String, Object> symbolQuery(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("symbol", symbol);
        return query;
    }

    private static void putRange(Map<String, Object> query, Instant startTime, Instant endTime, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (startTime != null) {
            query.put("startTime", startTime.toEpochMilli());
        }
        if (endTime != null) {
            query.put("endTime", endTime.toEpochMilli());
        }
        query.put("limit", limit);
    }

    /** A 5xx: the exchange is up but failing, which a retry may outlast. Never leaves this class. */
    private static final class TransientFailure extends IOException {

        private static final long serialVersionUID = 1L;

        TransientFailure(String message) {
            super(message);
        }
    }
}
