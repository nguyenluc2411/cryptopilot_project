package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.config.MarketApiProperties;
import com.cryptopilot.market.dto.response.ChartCandlesResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.ChartTimeframe;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.ChartCandleService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trading chart's candles at any of its sixteen timeframes (TR-35).
 *
 * <ul>
 *   <li><b>Stored timeframes</b> (15m, 1h, 4h, 1d; BR-08) are read from the database, as the analysis reads them.
 *   <li><b>The others</b> (1s on Spot, 1m, 3m, 5m, 30m, 2h, 6h, 8h, 12h, 3d, 1w, 1M) are read from the exchange,
 *       which builds them from its own trades, so they are exact rather than summed from stored candles of another
 *       grid; months and weeks start where the exchange starts them. A page is kept in Redis: a page of closed
 *       candles for {@link #PAST_TTL}, since they never change, the latest page for {@link #LIVE_TTL}, so many
 *       clients opening one chart cost the exchange one request. A cache that cannot be read is skipped.
 *   <li><b>One page per candle.</b> The cache key is {@code chart:market:symbol:tf:end}, {@code end} being {@code to}
 *       raised to the next boundary of the timeframe's grid (calendar month for 1M, the exchange's Monday week for
 *       1w, the UTC day for 3d, the candle length from the epoch otherwise): every {@code to} inside one candle asks
 *       for the same candles, those opened before it. The exchange is always asked for a full page
 *       ({@code maxCandles}) and the answer is cut to {@code limit} after the cache, so neither {@code to} nor
 *       {@code limit} can make each request miss the cache. A miss past {@code chartWeightPercent} of the venue's
 *       weight budget is refused, so chart readers cannot push the venue to the pause share the backfill and the
 *       alert replay depend on.
 *   <li><b>The forming candle</b>, asked for on the latest page, is the exchange's: its open, high, low and close so
 *       far, marked not closed. A stored timeframe takes it from the exchange too; only closed candles are stored.
 *   <li><b>The exchange unavailable</b> (rate limit, ban, outage, the chart's weight share used): 503, the request
 *       may be repeated.
 *   <li><b>No transaction.</b> The pair and the stored candles are read in short calls of their own; no database
 *       connection is held while Redis or the exchange answers.
 * </ul>
 *
 * <p>Rule: TR-35; BR-07, BR-08.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 "Bulkheads" (a share of
 * the exchange budget kept from chart readers) and "Timeouts" (no pooled connection held across a remote call).
 * Nottingham, M. &amp; Fielding, R. (2012). <i>RFC 6585</i>, §4 "429 Too Many Requests" (the per-address limit of the
 * endpoint, applied by {@code common.web.RateLimitFilter}).
 */
@Service
@RequiredArgsConstructor
public class ChartCandleServiceImpl implements ChartCandleService {

    static final Duration LIVE_TTL = Duration.ofSeconds(3);
    static final Duration PAST_TTL = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(ChartCandleServiceImpl.class);
    private static final TypeReference<List<ChartCandlesResponse.Candle>> PAGE = new TypeReference<>() {};

    private final MarketRequests requests;
    private final OhlcvRepository stored;
    private final BinanceRestClient exchange;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MarketApiProperties properties;
    private final Clock clock;

    @Override
    public ChartCandlesResponse candles(
            String market, String symbol, String timeframe, Integer limit, Instant to, boolean includeForming) {
        MarketType type = MarketRequests.market(market);
        ChartTimeframe tf = ChartTimeframe.fromCode(timeframe)
                .filter(candidate -> candidate.availableOn(type))
                .orElseThrow(() -> MarketRequests.invalid("tf must be a timeframe of the " + type
                        + " chart: 1s (Spot only), 1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w or 1M, was "
                        + timeframe));
        int count = limit == null ? properties.defaultCandles() : limit;
        if (count < 1 || count > properties.maxCandles()) {
            throw MarketRequests.invalid("limit must be between 1 and " + properties.maxCandles() + ", was " + count);
        }
        Instant now = clock.instant();
        if (to != null && to.isAfter(now)) {
            throw MarketRequests.invalid("to must not be in the future, was " + to);
        }
        CryptoPair pair = requests.enabledPair(type, symbol);
        boolean forming = includeForming && to == null;
        List<ChartCandlesResponse.Candle> candles;
        String source;
        if (tf.stored().isPresent()) {
            source = "STORED";
            candles =
                    new ArrayList<>(stored
                            .closedCandles(
                                    pair.getId(), type, tf.stored().get().code(), null, to == null ? now : to, count)
                            .stream()
                            .map(ChartCandleServiceImpl::candle)
                            .toList());
            if (forming) {
                formingCandle(type, pair.getSymbol(), tf, now)
                        .filter(open -> candles.isEmpty()
                                || open.openTime().isAfter(candles.getLast().openTime()))
                        .ifPresent(candles::add);
            }
        } else {
            source = "EXCHANGE";
            List<ChartCandlesResponse.Candle> page = page(type, pair.getSymbol(), tf, to, now);
            candles = new ArrayList<>(page.subList(Math.max(0, page.size() - count), page.size()));
            if (!forming && !candles.isEmpty() && !candles.getLast().closed()) {
                candles.removeLast();
            }
        }
        return new ChartCandlesResponse(pair.getSymbol(), type.name(), tf.code(), source, List.copyOf(candles));
    }

    /**
     * One full page of the exchange's candles opened before {@code to} (the latest when {@code null}), the forming one
     * last when the page reaches it, through the cache.
     */
    private List<ChartCandlesResponse.Candle> page(
            MarketType type, String symbol, ChartTimeframe tf, Instant to, Instant now) {
        Instant end = to == null ? null : pageEnd(tf, to);
        String key =
                "chart:" + type + ":" + symbol + ":" + tf.code() + ":" + (end == null ? "live" : end.toEpochMilli());
        Optional<List<ChartCandlesResponse.Candle>> cached = cached(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        requireChartWeight(type, symbol, tf);
        List<ChartCandlesResponse.Candle> fetched =
                fetch(type, symbol, tf, end == null ? null : end.minusMillis(1), properties.maxCandles(), now);
        boolean closed = end != null && fetched.stream().allMatch(ChartCandlesResponse.Candle::closed);
        store(key, fetched, closed ? PAST_TTL : LIVE_TTL);
        return fetched;
    }

    /**
     * The first boundary of the timeframe's grid at or after {@code to}. The candles opened before it are those opened
     * before {@code to}, since every candle opens on the grid, so every {@code to} inside one candle shares one page.
     */
    static Instant pageEnd(ChartTimeframe tf, Instant to) {
        ZonedDateTime utc = to.atZone(ZoneOffset.UTC);
        ZonedDateTime start;
        ZonedDateTime next;
        switch (tf) {
            case ONE_MONTH -> {
                start = utc.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
                next = start.plusMonths(1);
            }
            case ONE_WEEK -> {
                start = utc.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                next = start.plusWeeks(1);
            }
            // The exchange does not document its 3-day grid; every 3-day candle opens at a UTC midnight.
            case THREE_DAYS -> {
                start = utc.truncatedTo(ChronoUnit.DAYS);
                next = start.plusDays(1);
            }
            default -> {
                long step = tf.length().toMillis();
                long floor = Math.floorDiv(to.toEpochMilli(), step) * step;
                return floor == to.toEpochMilli() ? to : Instant.ofEpochMilli(floor + step);
            }
        }
        return start.toInstant().equals(to) ? to : next.toInstant();
    }

    /** Refuses a page the cache misses once the venue's used weight reaches the chart's share of its budget. */
    private void requireChartWeight(MarketType type, String symbol, ChartTimeframe tf) {
        BinanceVenue venue = venueOf(type);
        long share = (long) exchange.weightPerMinute(venue) * properties.chartWeightPercent() / 100;
        if (share > 0 && exchange.usedWeightThisMinute(venue) >= share) {
            log.info(
                    "TR-35 {} {} {} candles not read: the chart's {}% of the weight budget is used",
                    type, symbol, tf.code(), properties.chartWeightPercent());
            throw new BusinessException(
                    ErrorCode.MARKET_DATA_UNAVAILABLE, "the exchange cannot be read now; try again shortly");
        }
    }

    private Optional<ChartCandlesResponse.Candle> formingCandle(
            MarketType type, String symbol, ChartTimeframe tf, Instant now) {
        return page(type, symbol, tf, null, now).stream()
                .filter(candle -> !candle.closed())
                .findFirst();
    }

    private List<ChartCandlesResponse.Candle> fetch(
            MarketType type, String symbol, ChartTimeframe tf, Instant endTime, int count, Instant now) {
        List<Kline> klines;
        try {
            klines = exchange.klines(venueOf(type), symbol, tf.code(), null, endTime, count);
        } catch (RuntimeException unavailable) {
            log.warn(
                    "TR-35 {} {} {} candles not read from the exchange: {}",
                    type,
                    symbol,
                    tf.code(),
                    unavailable.toString());
            throw new BusinessException(
                    ErrorCode.MARKET_DATA_UNAVAILABLE, "the exchange cannot be read now; try again shortly");
        }
        return klines.stream()
                .map(kline -> new ChartCandlesResponse.Candle(
                        kline.openTime(),
                        kline.closeTime(),
                        kline.open(),
                        kline.high(),
                        kline.low(),
                        kline.close(),
                        kline.volume(),
                        kline.quoteVolume(),
                        kline.tradeCount(),
                        kline.isClosedAt(now)))
                .toList();
    }

    private Optional<List<ChartCandlesResponse.Candle>> cached(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, PAGE));
        } catch (RuntimeException unreadable) {
            log.debug("TR-35 chart cache {} not read: {}", key, unreadable.toString());
            return Optional.empty();
        }
    }

    private void store(String key, List<ChartCandlesResponse.Candle> candles, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(candles), ttl);
        } catch (RuntimeException unwritable) {
            log.debug("TR-35 chart cache {} not written: {}", key, unwritable.toString());
        }
    }

    private static ChartCandlesResponse.Candle candle(StoredCandle candle) {
        return new ChartCandlesResponse.Candle(
                candle.openTime(),
                candle.closeTime(),
                candle.open(),
                candle.high(),
                candle.low(),
                candle.close(),
                candle.baseVolume(),
                candle.quoteVolume(),
                candle.tradeCount() == null ? null : candle.tradeCount().longValue(),
                true);
    }

    private static BinanceVenue venueOf(MarketType market) {
        return market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
    }
}
