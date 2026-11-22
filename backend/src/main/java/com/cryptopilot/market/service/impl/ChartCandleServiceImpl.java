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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trading chart's candles at any of its sixteen timeframes (TR-35).
 *
 * <ul>
 *   <li><b>Stored timeframes</b> (15m, 1h, 4h, 1d; BR-08) are read from the database, as the analysis reads them.
 *   <li><b>The others</b> (1s on Spot, 1m, 3m, 5m, 30m, 2h, 6h, 8h, 12h, 3d, 1w, 1M) are read from the exchange,
 *       which builds them from its own trades, so they are exact rather than summed from stored candles of another
 *       grid; months and weeks start where the exchange starts them. A page is kept in Redis: a page of the past for
 *       {@link #PAST_TTL}, since closed candles never change, the latest page for {@link #LIVE_TTL}, so many clients
 *       opening one chart cost the exchange one request. A cache that cannot be read is skipped.
 *   <li><b>The forming candle</b>, asked for on the latest page, is the exchange's: its open, high, low and close so
 *       far, marked not closed. A stored timeframe takes it from the exchange too; only closed candles are stored.
 *   <li><b>The exchange unavailable</b> (rate limit, ban, outage): 503, the request may be repeated.
 * </ul>
 *
 * <p>Rule: TR-35; BR-07, BR-08.
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
    @Transactional(readOnly = true)
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
            candles = new ArrayList<>(page(type, pair.getSymbol(), tf, count, to, now));
            if (!forming && !candles.isEmpty() && !candles.getLast().closed()) {
                candles.removeLast();
            }
        }
        return new ChartCandlesResponse(pair.getSymbol(), type.name(), tf.code(), source, List.copyOf(candles));
    }

    /** One page of the exchange's candles, the forming one last when the page is the latest, through the cache. */
    private List<ChartCandlesResponse.Candle> page(
            MarketType type, String symbol, ChartTimeframe tf, int count, Instant to, Instant now) {
        String key = "chart:" + type + ":" + symbol + ":" + tf.code() + ":" + count + ":"
                + (to == null ? "live" : to.toEpochMilli());
        Optional<List<ChartCandlesResponse.Candle>> cached = cached(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<ChartCandlesResponse.Candle> fetched =
                fetch(type, symbol, tf, to == null ? null : to.minusMillis(1), count, now);
        store(key, fetched, to == null ? LIVE_TTL : PAST_TTL);
        return fetched;
    }

    private Optional<ChartCandlesResponse.Candle> formingCandle(
            MarketType type, String symbol, ChartTimeframe tf, Instant now) {
        return page(type, symbol, tf, 1, null, now).stream()
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
