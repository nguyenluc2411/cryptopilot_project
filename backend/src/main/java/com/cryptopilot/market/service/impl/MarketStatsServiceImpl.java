package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.config.MarketApiProperties;
import com.cryptopilot.market.dto.response.FundingSettlementResponse;
import com.cryptopilot.market.dto.response.FuturesMetricsResponse;
import com.cryptopilot.market.dto.response.FuturesPriceResponse;
import com.cryptopilot.market.dto.response.FuturesStatsResponse;
import com.cryptopilot.market.dto.response.LongShortRatioResponse;
import com.cryptopilot.market.dto.response.OpenInterestResponse;
import com.cryptopilot.market.dto.response.SpotStatsResponse;
import com.cryptopilot.market.dto.response.SpotTickerResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.FundingSettlement;
import com.cryptopilot.market.model.FuturesPriceSnapshot;
import com.cryptopilot.market.model.LongShortReading;
import com.cryptopilot.market.model.OpenInterestReading;
import com.cryptopilot.market.model.SpotSnapshot;
import com.cryptopilot.market.repository.MarketDataQueryRepository;
import com.cryptopilot.market.service.MarketStatsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statistics and futures metrics of UC-09, read from the stored snapshots.
 *
 * <p>Every value is answered with the instant it was stored for, and each source separately: the mark price of the
 * minute (NSF-03) and the open interest of the 5-minute reading (NSF-04) are usually different rows, and a row that
 * NSF-04 created before any price reached it has no price to give — the price block then comes from the latest row
 * that has one, or is absent. Series list only the instants that hold a value; nothing is carried forward,
 * interpolated or replaced by zero (D-45). Settled funding rates come from {@code funding_rate_history}, whose
 * instants are already normalized to the minute (D-46).
 *
 * <p>Rule: UC-09, BR-07, BR-10, BR-11; NSF-03, NSF-04; SRS 3.3.1, 3.3.3; D-45, D-46.
 */
@Service
public class MarketStatsServiceImpl implements MarketStatsService {

    private final MarketRequests requests;
    private final MarketDataQueryRepository data;
    private final MarketApiProperties properties;
    private final Clock clock;

    public MarketStatsServiceImpl(
            MarketRequests requests, MarketDataQueryRepository data, MarketApiProperties properties, Clock clock) {
        this.requests = requests;
        this.data = data;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SpotStatsResponse spotStats(String symbol) {
        CryptoPair pair = requests.enabledPair(MarketType.SPOT, symbol);
        SpotTickerResponse ticker = data.latestSpot(pair.getId())
                .map(MarketStatsServiceImpl::ticker)
                .orElse(null);
        return new SpotStatsResponse(pair.getSymbol(), clock.instant(), ticker);
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesStatsResponse futuresStats(String symbol) {
        CryptoPair pair = requests.enabledPair(MarketType.FUTURES, symbol);
        UUID id = pair.getId();
        return new FuturesStatsResponse(
                pair.getSymbol(),
                clock.instant(),
                data.latestFuturesPrice(id).map(MarketStatsServiceImpl::price).orElse(null),
                data.latestOpenInterest(id)
                        .map(MarketStatsServiceImpl::openInterest)
                        .orElse(null),
                data.latestLongShortRatio(id)
                        .map(MarketStatsServiceImpl::longShort)
                        .orElse(null),
                data.latestFundingSettlement(id)
                        .map(MarketStatsServiceImpl::settlement)
                        .orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesMetricsResponse futuresMetrics(String symbol, Instant from, Instant to) {
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(properties.defaultMetricsRange()) : from;
        MarketRequests.requireRange(start, end, now);
        if (Duration.between(start, end).compareTo(properties.maxMetricsRange()) > 0) {
            throw MarketRequests.invalid("the range must not be longer than " + properties.maxMetricsRange() + ", was "
                    + Duration.between(start, end));
        }
        CryptoPair pair = requests.enabledPair(MarketType.FUTURES, symbol);
        UUID id = pair.getId();
        return new FuturesMetricsResponse(
                pair.getSymbol(),
                start,
                end,
                data.openInterestBetween(id, start, end).stream()
                        .map(MarketStatsServiceImpl::openInterest)
                        .toList(),
                data.longShortRatiosBetween(id, start, end).stream()
                        .map(MarketStatsServiceImpl::longShort)
                        .toList(),
                data.settlementsBetween(id, start, end).stream()
                        .map(MarketStatsServiceImpl::settlement)
                        .toList(),
                new FuturesMetricsResponse.AvailableFrom(
                        data.earliestOpenInterest(id).orElse(null),
                        data.earliestLongShortRatio(id).orElse(null),
                        data.earliestFundingSettlement(id).orElse(null)));
    }

    private static SpotTickerResponse ticker(SpotSnapshot snapshot) {
        return new SpotTickerResponse(
                snapshot.snapshotTime(),
                snapshot.lastPrice(),
                snapshot.bestBid(),
                snapshot.bestAsk(),
                snapshot.high24h(),
                snapshot.low24h(),
                snapshot.changePercent24h(),
                snapshot.baseVolume24h(),
                snapshot.quoteVolume24h());
    }

    private static FuturesPriceResponse price(FuturesPriceSnapshot snapshot) {
        return new FuturesPriceResponse(
                snapshot.snapshotTime(),
                snapshot.markPrice(),
                snapshot.indexPrice(),
                snapshot.fundingRate(),
                snapshot.nextFundingTime());
    }

    private static OpenInterestResponse openInterest(OpenInterestReading reading) {
        return new OpenInterestResponse(reading.time(), reading.openInterest(), reading.openInterestValue());
    }

    private static LongShortRatioResponse longShort(LongShortReading reading) {
        return new LongShortRatioResponse(
                reading.time(), reading.longShortRatio(), reading.longAccountRatio(), reading.shortAccountRatio());
    }

    private static FundingSettlementResponse settlement(FundingSettlement settlement) {
        return new FundingSettlementResponse(
                settlement.fundingTime(), settlement.fundingRate(), settlement.markPrice());
    }
}
