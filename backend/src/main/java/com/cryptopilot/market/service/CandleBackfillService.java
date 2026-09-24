package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.BinanceVenue;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.StoredGap;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-02: brings every stored candle series of one market up to date with the exchange's closed candles.
 *
 * <h2>Which series</h2>
 *
 * <p>Every pair whose exchange status on this market is {@code TRADING}, whatever the administrator's switches
 * say, for each timeframe BR-08 stores (15m, 1h, 4h, 1d), each fetched natively and never derived from a
 * smaller one. History has to exist before an administrator activates a pair — a chart and the 200 candles
 * NSF-05 needs cannot wait for the first day of trading — and activation itself stays manual (BR-07, D-42).
 * {@code NOT_TRADING}, {@code DELISTED} and a market the pair is not listed on (no status, Q-14) are skipped.
 *
 * <h2>How a series is filled</h2>
 *
 * <p>From the candle after the latest stored one, or, for an empty series, from the configured depth back
 * (D-41); forward in pages until the exchange has nothing newer. The resume point is the data itself, so an
 * interrupted run continues exactly where it stopped. A pair listed after the start simply begins at the
 * first candle the exchange returns. Only candles whose close time is before now are kept (BR-08): the
 * forming candle is never stored, and the next run picks it up once it has closed.
 *
 * <p>Each page is written in its own transaction, after the exchange call has returned, with
 * {@code ON CONFLICT DO NOTHING}: a crash between pages loses nothing and a repeated page inserts nothing.
 * Prices and volumes stay {@code BigDecimal} from the exchange's strings to the {@code numeric} columns.
 *
 * <h2>Leaving room for others</h2>
 *
 * <p>The weight budget of a venue is shared by every job that calls it. Before each page the backfill reads the
 * weight used this minute and stops the run at the configured share of the budget (50 % by default), well
 * under the 80 % at which the client stops everybody, so NSF-01 and the other jobs keep working. The run
 * reports the instant it may continue — the next minute — and the scheduler resumes it then; nothing here
 * sleeps.
 *
 * <h2>When the exchange refuses</h2>
 *
 * <p>A {@code REJECTED} or {@code MALFORMED} answer is a defect of that series: logged, skipped for this run,
 * the others carry on. Every other refusal — rate limit, ban, open circuit, outage — stops the whole run by
 * propagating to the job, which applies the client's caller contract.
 *
 * <p>Rule: NSF-02; BR-07, BR-08, BR-09; D-41, D-42; TECHNICAL_DESIGN 7.1 and 7.1.2.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11
 * (processing that can be resumed from its own output and repeated without duplicates).
 * <p>Reference: Binance. <i>Spot API documentation</i>, "Kline/Candlestick data" and <i>USDⓈ-M Futures</i>,
 * "Kline/Candlestick Data" (klines are identified by their open time; limits and weights in
 * TECHNICAL_DESIGN 7.1.1).
 */
@Service
public class CandleBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CandleBackfillService.class);

    /** The timeframes BR-08 stores, each fetched natively. */
    static final List<MarketInterval> TIMEFRAMES = List.of(
            MarketInterval.FIFTEEN_MINUTES, MarketInterval.ONE_HOUR, MarketInterval.FOUR_HOURS, MarketInterval.ONE_DAY);

    private final BinanceRestClient exchange;
    private final CryptoPairRepository pairs;
    private final OhlcvRepository candles;
    private final CandleBackfillProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CandleBackfillService(
            BinanceRestClient exchange,
            CryptoPairRepository pairs,
            OhlcvRepository candles,
            CandleBackfillProperties properties,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.exchange = exchange;
        this.pairs = pairs;
        this.candles = candles;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * Backfills one market until every series is current, the weight share is spent, or the exchange refuses.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}; what was
     *     written before it stays written
     */
    public BackfillRun backfill(MarketType market) {
        Instant now = clock.instant();
        BinanceVenue venue = venueOf(market);
        List<CryptoPair> targets = pairs.findAllForSync().stream()
                .filter(pair -> pair.exchangeStatus(market) == ExchangeStatus.TRADING)
                .toList();
        int completed = 0;
        int inserted = 0;
        List<String> defects = new ArrayList<>();
        for (CryptoPair pair : targets) {
            for (MarketInterval timeframe : TIMEFRAMES) {
                String series = market + " " + pair.getSymbol() + " " + timeframe.code();
                try {
                    SeriesResult result = backfillSeries(
                            pair.getId(),
                            pair.getSymbol(),
                            market,
                            venue,
                            timeframe,
                            resumePoint(pair, market, timeframe, now),
                            null,
                            now);
                    inserted += result.inserted();
                    if (result.pausedUntil().isPresent()) {
                        log.info(
                                "NSF-02 {}: paused at the weight share until {}",
                                series,
                                result.pausedUntil().get());
                        return new BackfillRun(market, completed, inserted, result.pausedUntil(), defects);
                    }
                    completed++;
                } catch (BinanceClientException refusal) {
                    if (refusal.kind() != BinanceClientException.Kind.REJECTED
                            && refusal.kind() != BinanceClientException.Kind.MALFORMED) {
                        throw refusal;
                    }
                    log.error(
                            "NSF-02 {} defect, skipped this run: {} — {}",
                            series,
                            refusal.kind(),
                            refusal.getMessage(),
                            refusal);
                    defects.add(series);
                }
            }
        }
        return new BackfillRun(market, completed, inserted, Optional.empty(), defects);
    }

    /**
     * Fills one gap NSF-03 detected: the candles opened from {@code gap.from()} to {@code gap.to()}, and no later
     * one — the stream is storing those. Paged, paced and written exactly as a series is, so a gap costs what its
     * candles cost and is safe to repeat.
     *
     * <p>A gap the exchange rejects, or answers with a body that cannot be read, is a defect: logged and dropped,
     * because asking again would get the same answer. Every other refusal propagates to the job, which keeps the
     * gap and continues it at {@code retryAt} or with the next run.
     *
     * <p>Rule: NSF-02, NSF-03 (any detected gap triggers NSF-02); BR-08; TECHNICAL_DESIGN 7.1 steps 4 and 5; A-33.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}
     */
    public GapFill fillGap(GapDetected gap) {
        Instant now = clock.instant();
        MarketInterval timeframe = MarketInterval.fromCode(gap.timeframe())
                .orElseThrow(() -> new IllegalArgumentException("not a stored timeframe: " + gap.timeframe()));
        try {
            SeriesResult result = backfillSeries(
                    gap.pairId(),
                    gap.symbol(),
                    gap.market(),
                    venueOf(gap.market()),
                    timeframe,
                    gap.from(),
                    gap.to(),
                    now);
            if (result.pausedUntil().isEmpty()) {
                return new GapFill(result.inserted(), Optional.empty(), Optional.empty());
            }
            GapDetected rest = result.lastStored()
                    .map(last -> gap.startingAt(last.plus(timeframe.duration())))
                    .orElse(gap);
            return new GapFill(result.inserted(), Optional.of(rest), result.pausedUntil());
        } catch (BinanceClientException refusal) {
            if (refusal.kind() != BinanceClientException.Kind.REJECTED
                    && refusal.kind() != BinanceClientException.Kind.MALFORMED) {
                throw refusal;
            }
            log.error(
                    "NSF-02 gap {} {} {} {} -> {} dropped as a defect: {} — {}",
                    gap.market(),
                    gap.symbol(),
                    gap.timeframe(),
                    gap.from(),
                    gap.to(),
                    refusal.kind(),
                    refusal.getMessage(),
                    refusal);
            return new GapFill(0, Optional.empty(), Optional.empty());
        }
    }

    /**
     * The holes inside the stored series of the last {@code gapScanWindow}, as gaps for {@link #fillGap}, for the
     * pairs this backfill targets (D-42). Run at start-up, it finds again whatever a gap reported before a restart
     * left unfilled — the queue of reported gaps lives in memory — and anything else missing inside a series.
     *
     * <p>A hole the exchange itself has (a maintenance window) is found at every start-up and costs one request
     * that returns nothing.
     *
     * <p>Rule: NSF-02, NSF-03 (gap detection); A-33.
     */
    public List<GapDetected> storedGaps() {
        Instant since = clock.instant().minus(properties.gapScanWindow());
        Map<UUID, CryptoPair> targets = new HashMap<>();
        for (CryptoPair pair : pairs.findAllForSync()) {
            targets.put(pair.getId(), pair);
        }
        List<GapDetected> gaps = new ArrayList<>();
        for (StoredGap hole : candles.gapsSince(since)) {
            CryptoPair pair = targets.get(hole.pairId());
            if (pair == null || pair.exchangeStatus(hole.market()) != ExchangeStatus.TRADING) {
                continue;
            }
            Duration length =
                    MarketInterval.fromCode(hole.timeframe()).orElseThrow().duration();
            gaps.add(new GapDetected(
                    pair.getId(),
                    pair.getSymbol(),
                    hole.market(),
                    hole.timeframe(),
                    hole.lastBefore().plus(length),
                    hole.firstAfter().minus(length)));
        }
        return gaps;
    }

    /**
     * Where a series with no stored candle starts: the configured depth of its timeframe back from now (D-41).
     * NSF-03 uses it when the stream reaches a series before the backfill has.
     */
    public Instant seriesStart(MarketInterval timeframe, Instant now) {
        return now.minus(depthOf(timeframe));
    }

    private Instant resumePoint(CryptoPair pair, MarketType market, MarketInterval timeframe, Instant now) {
        return candles.latestOpenTime(pair.getId(), market, timeframe.code())
                .map(latest -> latest.plusMillis(1))
                .orElseGet(() -> seriesStart(timeframe, now));
    }

    /**
     * Fetches and stores the closed candles of one series opened from {@code from}, up to {@code lastOpen}
     * inclusive when given, else up to now.
     */
    private SeriesResult backfillSeries(
            UUID pairId,
            String symbol,
            MarketType market,
            BinanceVenue venue,
            MarketInterval timeframe,
            Instant from,
            Instant lastOpen,
            Instant now) {
        Instant endTime =
                lastOpen == null ? null : lastOpen.plus(timeframe.duration()).minusMillis(1);
        int pageSize = market == MarketType.SPOT
                ? properties.pageSize().spot()
                : properties.pageSize().futures();
        Instant cursor = from;
        Instant lastStored = null;
        int inserted = 0;
        while (true) {
            Optional<Instant> pause = budgetPause(venue);
            if (pause.isPresent()) {
                logProgress(market, symbol, timeframe, from, lastStored, inserted);
                return new SeriesResult(inserted, pause, Optional.ofNullable(lastStored));
            }
            List<Kline> page = exchange.klines(venue, symbol, timeframe, cursor, endTime, pageSize);
            List<Kline> closed = page.stream()
                    .filter(kline -> kline.isClosedAt(now))
                    .filter(kline -> lastOpen == null || !kline.openTime().isAfter(lastOpen))
                    .toList();
            Integer written =
                    transaction.execute(status -> candles.insertAll(pairId, market, timeframe.code(), closed));
            inserted += written == null ? 0 : written;
            if (!closed.isEmpty()) {
                lastStored = closed.getLast().openTime();
                cursor = lastStored.plusMillis(1);
            }
            if (page.size() < pageSize || closed.size() < page.size()) {
                logProgress(market, symbol, timeframe, from, lastStored, inserted);
                return new SeriesResult(inserted, Optional.empty(), Optional.ofNullable(lastStored));
            }
        }
    }

    /** The next minute, when this venue's weight used this minute has reached the backfill's share. */
    private Optional<Instant> budgetPause(BinanceVenue venue) {
        int share = exchange.weightPerMinute(venue) * properties.budgetSharePercent() / 100;
        if (exchange.usedWeightThisMinute(venue) < share) {
            return Optional.empty();
        }
        return Optional.of(clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1)));
    }

    private Duration depthOf(MarketInterval timeframe) {
        CandleBackfillProperties.Depth depth = properties.depth();
        return switch (timeframe) {
            case FIFTEEN_MINUTES -> depth.fifteenMinutes();
            case ONE_HOUR -> depth.oneHour();
            case FOUR_HOURS -> depth.fourHours();
            default -> depth.oneDay();
        };
    }

    private static void logProgress(
            MarketType market, String symbol, MarketInterval timeframe, Instant from, Instant to, int inserted) {
        log.info(
                "NSF-02 {} {} {}: {} -> {}, {} rows inserted",
                market,
                symbol,
                timeframe.code(),
                from,
                to == null ? "(nothing new)" : to,
                inserted);
    }

    private static BinanceVenue venueOf(MarketType market) {
        return market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
    }

    /** One series: how many candles were new, when to continue if the budget stopped it, the last stored. */
    private record SeriesResult(int inserted, Optional<Instant> pausedUntil, Optional<Instant> lastStored) {}
}
