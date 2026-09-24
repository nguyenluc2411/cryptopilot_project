package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-03's storage side: which pairs are streamed, and what happens to a candle the exchange closes.
 *
 * <h2>Which pairs</h2>
 *
 * <p>The pairs an administrator enabled on the market (BR-07: only enabled pairs and markets are collected)
 * whose exchange status there is {@code TRADING}; a pair the exchange stopped trading has no stream to read.
 * Unlike the backfill (D-42), activation matters here: the stream is what the product shows and trades against.
 *
 * <h2>A closed candle</h2>
 *
 * <ol>
 *   <li>Only a candle the exchange marks closed, of a timeframe BR-08 stores (15m, 1h, 4h, 1d), is written —
 *       {@code INSERT … ON CONFLICT DO NOTHING}, in its own transaction, so a candle the backfill already stored
 *       costs nothing.
 *   <li><b>Gap detection</b> (TECHNICAL_DESIGN 7.1 step 4). The open time of the latest candle of the series is
 *       remembered, read from {@code ohlcv} the first time the series is seen. A candle that opens later than
 *       one timeframe after it means candles are missing between them — the stream was down, the application
 *       restarted, a close never arrived — and {@link GapDetected} names them for NSF-02. A series with no
 *       stored candle at all is missing its whole history back to the configured depth, and the gap says so:
 *       the backfill resumes after the latest stored candle, so without this it would never go back behind the
 *       one the stream has just written. A candle at or before the latest is a repeat: no gap.
 *   <li>{@link CandleClosed} is published after the commit, for every close, for NSF-05.
 * </ol>
 *
 * <p>Candles of one pair reach this class in order, from one consumer (TECHNICAL_DESIGN 10: partitioned by
 * pair), so the remembered open time of a series is read and written by one thread at a time.
 *
 * <p>Rule: NSF-03, NSF-02; BR-07, BR-08; TECHNICAL_DESIGN 7.1 steps 3 and 4; A-33.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11 (a
 * consumer that detects a missing range from sequence gaps and re-reads it from the source of record).
 */
@Service
public class StreamCandleService {

    private static final Logger log = LoggerFactory.getLogger(StreamCandleService.class);

    private final CryptoPairRepository pairs;
    private final OhlcvRepository candles;
    private final CandleBackfillService backfill;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<Series, Instant> latestOpen = new ConcurrentHashMap<>();

    public StreamCandleService(
            CryptoPairRepository pairs,
            OhlcvRepository candles,
            CandleBackfillService backfill,
            PlatformTransactionManager transactions,
            ApplicationEventPublisher events,
            Clock clock) {
        this.pairs = pairs;
        this.candles = candles;
        this.backfill = backfill;
        this.transaction = new TransactionTemplate(transactions);
        this.events = events;
        this.clock = clock;
    }

    /** The pairs to stream on a market: enabled there by an administrator (BR-07) and trading on the exchange. */
    public List<StreamTarget> targets(MarketType market) {
        return pairs.findEnabledOn(market).stream()
                .filter(pair -> pair.exchangeStatus(market) == ExchangeStatus.TRADING)
                .map(StreamCandleService::target)
                .toList();
    }

    /**
     * Stores a closed candle of a stored timeframe, reports the gap before it if there is one, and announces the
     * close. Anything else — a forming candle, a 1m candle — is ignored.
     *
     * <p>Rule: BR-08; NSF-03; TECHNICAL_DESIGN 7.1 steps 3 and 4.
     *
     * @return whether the candle was a stored close (written now or already present)
     */
    public boolean onKline(MarketType market, StreamTarget target, KlineMessage message) {
        if (!message.closed() || !CandleBackfillService.TIMEFRAMES.contains(message.interval())) {
            return false;
        }
        MarketInterval timeframe = message.interval();
        Kline kline = message.kline();
        Series series = new Series(target.pairId(), market, timeframe);
        Optional<Instant> previous = Optional.ofNullable(latestOpen.get(series))
                .or(() -> candles.latestOpenTime(target.pairId(), market, timeframe.code()));
        transaction.executeWithoutResult(
                status -> candles.insertAll(target.pairId(), market, timeframe.code(), List.of(kline)));
        latestOpen.merge(series, kline.openTime(), (a, b) -> a.isAfter(b) ? a : b);

        gapBefore(market, target, timeframe, kline.openTime(), previous).ifPresent(gap -> {
            log.info(
                    "NSF-03 {} {} {}: candles {} -> {} missing; NSF-02 fills them",
                    market,
                    target.symbol(),
                    timeframe.code(),
                    gap.from(),
                    gap.to());
            events.publishEvent(gap);
        });
        events.publishEvent(new CandleClosed(
                target.pairId(),
                target.symbol(),
                market,
                timeframe.code(),
                kline.openTime(),
                kline.closeTime(),
                kline.open(),
                kline.high(),
                kline.low(),
                kline.close(),
                kline.volume(),
                kline.quoteVolume()));
        return true;
    }

    /**
     * The missing candles between the latest stored and this one, if any.
     *
     * <p>Rule: NSF-03 (gap detection), TECHNICAL_DESIGN 7.1 step 4.
     */
    private Optional<GapDetected> gapBefore(
            MarketType market,
            StreamTarget target,
            MarketInterval timeframe,
            Instant open,
            Optional<Instant> previous) {
        Instant from = previous.map(latest -> latest.plus(timeframe.duration()))
                .orElseGet(() -> backfill.seriesStart(timeframe, clock.instant()));
        Instant to = open.minus(timeframe.duration());
        if (from.isAfter(to)) {
            return Optional.empty();
        }
        return Optional.of(new GapDetected(target.pairId(), target.symbol(), market, timeframe.code(), from, to));
    }

    private static StreamTarget target(CryptoPair pair) {
        return new StreamTarget(pair.getId(), pair.getSymbol());
    }

    /** One stored series. */
    private record Series(UUID pairId, MarketType market, MarketInterval timeframe) {}
}
