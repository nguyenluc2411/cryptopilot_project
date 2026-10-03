package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.service.MinuteKlineService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches one page of a pair's closed 1-minute candles from the exchange's kline endpoint, on the same request gate
 * and weight budget as the backfill of NSF-02: when the venue has used its backfill share of the minute's weight, or
 * the exchange refuses (429, 418, open circuit, outage), the caller gets the instant to ask again instead of an
 * exception. A request the exchange rejects as wrong, or an answer it cannot read, ends the replay of that pair with an
 * empty page and a warning, since asking again would get the same answer.
 *
 * <p>Only candles closed at this instant are returned (BR-08): the endpoint also answers the one still forming.
 *
 * <p>Rule: NSF-07, BR-08, BR-09; TECHNICAL_DESIGN 7.1.2 and 7.7; A-04.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 (a caller that is
 * refused backs off until the given time rather than retrying at once).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MinuteKlineServiceImpl implements MinuteKlineService {

    private static final Logger log = LoggerFactory.getLogger(MinuteKlineServiceImpl.class);

    private final BinanceRestClient exchange;
    private final CryptoPairRepository pairs;
    private final CandleBackfillProperties properties;
    private final Clock clock;

    /** No transaction: the pair is read in the repository's own short one, so no connection is held over HTTP. */
    @Override
    public MinuteKlineBatch closedMinuteKlines(MarketType market, UUID pairId, Instant from) {
        Optional<String> symbol =
                pairs.findById(pairId).filter(pair -> pair.isEnabledOn(market)).map(CryptoPair::getSymbol);
        if (symbol.isEmpty()) {
            return MinuteKlineBatch.of(List.of());
        }
        BinanceVenue venue = market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
        Optional<Instant> pause = budgetPause(venue);
        if (pause.isPresent()) {
            return MinuteKlineBatch.refusedUntil(pause.get());
        }
        int pageSize = market == MarketType.SPOT
                ? properties.pageSize().spot()
                : properties.pageSize().futures();
        List<Kline> page;
        try {
            page = exchange.klines(venue, symbol.get(), MarketInterval.ONE_MINUTE, from, null, pageSize);
        } catch (BinanceClientException refusal) {
            if (refusal.kind() == BinanceClientException.Kind.REJECTED
                    || refusal.kind() == BinanceClientException.Kind.MALFORMED) {
                log.warn(
                        "NSF-07 {} {} 1m candles from {} not fetched: {}",
                        market,
                        symbol.get(),
                        from,
                        refusal.getMessage());
                return MinuteKlineBatch.of(List.of());
            }
            return MinuteKlineBatch.refusedUntil(refusal.retryAt().orElseGet(this::nextMinute));
        }
        Instant now = clock.instant();
        return MinuteKlineBatch.of(page.stream()
                .filter(kline -> kline.isClosedAt(now))
                .filter(kline -> !kline.openTime().isBefore(from))
                .map(kline -> new MinuteKline(
                        market, pairId, kline.openTime(), kline.low(), kline.high(), true, kline.closeTime()))
                .toList());
    }

    /** The next minute, when this venue's weight used this minute has reached the backfill's share. */
    private Optional<Instant> budgetPause(BinanceVenue venue) {
        int share = exchange.weightPerMinute(venue) * properties.budgetSharePercent() / 100;
        return exchange.usedWeightThisMinute(venue) < share ? Optional.empty() : Optional.of(nextMinute());
    }

    private Instant nextMinute() {
        return clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
    }
}
