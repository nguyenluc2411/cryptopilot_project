package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.config.MarketApiProperties;
import com.cryptopilot.market.dto.response.CandleResponse;
import com.cryptopilot.market.dto.response.CandlesResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.CandleService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The candles of UC-09: the stored closed candles of one series, bounded in number and in time.
 *
 * <p>Only the timeframes BR-08 stores are accepted. A request reads at most {@code maxCandles} candles and may not
 * name a range longer than that many candles of its timeframe, so no request reads a whole series. The candles
 * returned are the most recent ones of the range, oldest first; a client pages backwards by passing the first open
 * time it holds as the next {@code to}, which never skips or repeats a candle because open times are unique.
 *
 * <p>Rule: UC-09, BR-07, BR-08; SRS 3.3.1, 3.3.2; TECHNICAL_DESIGN 5.1 (MSG01, MSG41).
 */
@Service
public class CandleServiceImpl implements CandleService {

    private final MarketRequests requests;
    private final OhlcvRepository candles;
    private final MarketApiProperties properties;
    private final Clock clock;

    public CandleServiceImpl(
            MarketRequests requests, OhlcvRepository candles, MarketApiProperties properties, Clock clock) {
        this.requests = requests;
        this.candles = candles;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CandlesResponse closedCandles(
            String market, String symbol, String timeframe, Integer limit, Instant from, Instant to) {
        MarketType type = MarketRequests.market(market);
        MarketInterval interval = storedTimeframe(timeframe);
        int count = limit == null ? properties.defaultCandles() : limit;
        if (count < 1 || count > properties.maxCandles()) {
            throw MarketRequests.invalid("limit must be between 1 and " + properties.maxCandles() + ", was " + count);
        }
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        MarketRequests.requireRange(from, end, now);
        if (from != null) {
            Duration longest = interval.duration().multipliedBy(properties.maxCandles());
            if (Duration.between(from, end).compareTo(longest) > 0) {
                throw MarketRequests.invalid("the range holds more than " + properties.maxCandles() + " "
                        + interval.code() + " candles; ask for at most " + longest);
            }
        }
        CryptoPair pair = requests.enabledPair(type, symbol);
        List<CandleResponse> found =
                candles.closedCandles(pair.getId(), type, interval.code(), from, end, count).stream()
                        .map(CandleServiceImpl::response)
                        .toList();
        return new CandlesResponse(pair.getSymbol(), type.name(), interval.code(), found);
    }

    /** A timeframe BR-08 stores: 15m, 1h, 4h or 1d. */
    private static MarketInterval storedTimeframe(String timeframe) {
        return MarketInterval.fromCode(timeframe)
                .filter(CandleBackfillServiceImpl.TIMEFRAMES::contains)
                .orElseThrow(() -> MarketRequests.invalid("tf must be one of 15m, 1h, 4h, 1d, was " + timeframe));
    }

    private static CandleResponse response(StoredCandle candle) {
        return new CandleResponse(
                candle.openTime(),
                candle.closeTime(),
                candle.open(),
                candle.high(),
                candle.low(),
                candle.close(),
                candle.baseVolume(),
                candle.quoteVolume(),
                candle.tradeCount());
    }
}
