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
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
                    SeriesResult result = backfillSeries(pair, market, venue, timeframe, now);
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

    private SeriesResult backfillSeries(
            CryptoPair pair, MarketType market, BinanceVenue venue, MarketInterval timeframe, Instant now) {
        Instant from = candles.latestOpenTime(pair.getId(), market, timeframe.code())
                .map(latest -> latest.plusMillis(1))
                .orElseGet(() -> now.minus(depthOf(timeframe)));
        int pageSize = market == MarketType.SPOT
                ? properties.pageSize().spot()
                : properties.pageSize().futures();
        Instant cursor = from;
        Instant lastStored = null;
        int inserted = 0;
        while (true) {
            Optional<Instant> pause = budgetPause(venue);
            if (pause.isPresent()) {
                logProgress(market, pair, timeframe, from, lastStored, inserted);
                return new SeriesResult(inserted, pause);
            }
            List<Kline> page = exchange.klines(venue, pair.getSymbol(), timeframe, cursor, null, pageSize);
            List<Kline> closed =
                    page.stream().filter(kline -> kline.isClosedAt(now)).toList();
            Integer written =
                    transaction.execute(status -> candles.insertAll(pair.getId(), market, timeframe.code(), closed));
            inserted += written == null ? 0 : written;
            if (!closed.isEmpty()) {
                lastStored = closed.getLast().openTime();
                cursor = lastStored.plusMillis(1);
            }
            if (page.size() < pageSize || closed.size() < page.size()) {
                logProgress(market, pair, timeframe, from, lastStored, inserted);
                return new SeriesResult(inserted, Optional.empty());
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
            MarketType market, CryptoPair pair, MarketInterval timeframe, Instant from, Instant to, int inserted) {
        log.info(
                "NSF-02 {} {} {}: {} -> {}, {} rows inserted",
                market,
                pair.getSymbol(),
                timeframe.code(),
                from,
                to == null ? "(nothing new)" : to,
                inserted);
    }

    private static BinanceVenue venueOf(MarketType market) {
        return market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
    }

    /** One series: how many candles were new, and when to continue if the budget stopped it. */
    private record SeriesResult(int inserted, Optional<Instant> pausedUntil) {}
}
