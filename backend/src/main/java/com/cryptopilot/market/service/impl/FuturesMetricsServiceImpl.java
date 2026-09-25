package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.calculator.FundingSettlementDue;
import com.cryptopilot.market.calculator.FundingTimes;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.FundingInfo;
import com.cryptopilot.market.client.FundingRate;
import com.cryptopilot.market.client.LongShortRatio;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.client.OpenInterestStatistic;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.config.FuturesMetricsProperties;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.model.MetricsRun;
import com.cryptopilot.market.model.SettlementRun;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.FuturesMetricsRepository;
import com.cryptopilot.market.service.FuturesMetricsService;
import com.cryptopilot.market.service.LatestMarketData;
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
 * NSF-04: every five minutes collects the open interest and the global long/short account ratio of the futures
 * pairs, and at each funding time stores the settled funding rate.
 *
 * <h2>Which pairs</h2>
 *
 * <p>The pairs an administrator enabled on futures that the exchange trades there — the pairs NSF-03 streams
 * (BR-07, D-44).
 *
 * <h2>Open interest and long/short ratio</h2>
 *
 * <p>Read from the exchange's 5-minute histories, not from the present-value endpoint, for three reasons: each
 * reading carries the instant of its period, so every pair's rows land on the same 5-minute instants; the open
 * interest comes with its value, which SCR-11 charts; and readings missed while the application was down can
 * still be read, for up to the month the exchange keeps (BR-10). Each metric resumes from the latest instant
 * stored for it, or from {@code depth} back for a pair with none, and pages forward with both ends of the range
 * — the exchange answers only its latest entries to a request with a start and no end. Only instants before
 * the current minute are written; see {@link FuturesMetricsRepository} for why.
 *
 * <p>A period the exchange has not published yet is simply not there: nothing is written for it, the resume
 * point stays where it was and the next run asks again. Nothing is ever carried from one reading into another
 * minute's row.
 *
 * <h2>Funding settlements</h2>
 *
 * <p>For each pair, {@link FundingSettlementDue} decides from the next funding time and the funding interval —
 * both read from the exchange, never assumed (BR-11) — whether a settlement has passed that is not stored yet.
 * The next funding time is the streamed one while it is fresh ({@code markPriceMaxAge}), otherwise read from
 * {@code premiumIndex}. The interval comes from {@code fundingInfo}; a symbol it does not list has no interval,
 * and is checked on every run. A due pair's settled rates after the latest stored one ({@link FundingTimes#after})
 * are read from {@code fundingRate} and stored once each, at their normalized instant.
 *
 * <p>When the exchange changes a symbol's interval, nothing is lost or doubled: the settlements are read from the
 * latest stored one onwards whatever the interval, so the only effect of a funding information that lags behind
 * the stream is that a settlement is stored one interval later.
 *
 * <h2>When the exchange refuses</h2>
 *
 * <p>{@code REJECTED} and {@code MALFORMED} are defects of one series or pair: logged, skipped for this run.
 * Every other refusal stops the run by propagating to the job, which applies the caller contract of
 * TECHNICAL_DESIGN 7.1.2. A refused call writes nothing: rows come only from readings the exchange sent.
 *
 * <p>Rule: NSF-04; BR-07, BR-09, BR-10, BR-11, BR-37; TECHNICAL_DESIGN 7.1 step 8 and 7.1.2.
 *
 * <p>Reference: Binance. <i>USDⓈ-M Futures API</i>, "Open Interest Statistics", "Long/Short Ratio", "Mark
 * Price", "Get Funding Rate History" and "Get Funding Rate Info" (limits in TECHNICAL_DESIGN 7.1.1).
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11
 * (processing that resumes from its own output and can be repeated without duplicates).
 */
@Service
public class FuturesMetricsServiceImpl implements FuturesMetricsService {

    private static final Logger log = LoggerFactory.getLogger(FuturesMetricsService.class);

    /** The collection period of NSF-04, which is also the shortest period the exchange's histories offer. */
    static final MarketInterval PERIOD = MarketInterval.FIVE_MINUTES;

    /** The most settled rates the exchange answers per call. */
    static final int FUNDING_PAGE = 1000;

    private final BinanceRestClient exchange;
    private final CryptoPairRepository pairs;
    private final FuturesMetricsRepository metrics;
    private final LatestMarketData latest;
    private final FuturesMetricsProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public FuturesMetricsServiceImpl(
            BinanceRestClient exchange,
            CryptoPairRepository pairs,
            FuturesMetricsRepository metrics,
            LatestMarketData latest,
            FuturesMetricsProperties properties,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.exchange = exchange;
        this.pairs = pairs;
        this.metrics = metrics;
        this.latest = latest;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * Collects the open interest and long/short account ratio of every target pair up to the last period before
     * the current minute, or until the run's request limit.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}; what was
     *     written before it stays written
     */
    public MetricsRun collectMetrics() {
        Instant now = clock.instant();
        Instant cutoff = now.truncatedTo(ChronoUnit.MINUTES);
        Instant floor = alignUp(now.minus(properties.depth()));
        Counters counters = new Counters();
        List<String> defects = new ArrayList<>();
        for (CryptoPair pair : targets()) {
            for (Metric metric : Metric.values()) {
                String series = pair.getSymbol() + " " + metric.label;
                try {
                    if (!collect(pair, metric, floor, cutoff, counters)) {
                        return counters.run(true, defects);
                    }
                } catch (BinanceClientException refusal) {
                    if (!isDefect(refusal)) {
                        throw refusal;
                    }
                    log.error("NSF-04 {} defect, skipped this run: {}", series, refusal.getMessage(), refusal);
                    defects.add(series);
                }
            }
        }
        return counters.run(false, defects);
    }

    /**
     * Stores the settled funding rates every target pair has not stored yet.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}; what was
     *     stored before it stays stored
     */
    public SettlementRun settleFunding() {
        Instant now = clock.instant();
        List<CryptoPair> targets = targets();
        List<String> defects = new ArrayList<>();
        if (targets.isEmpty()) {
            return new SettlementRun(0, 0, 0, 0, defects);
        }
        Map<String, Duration> intervals = fundingIntervals(defects);
        int due = 0;
        int inserted = 0;
        int withoutMarkPrice = 0;
        for (CryptoPair pair : targets) {
            try {
                Optional<Instant> stored = metrics.latestFundingTime(pair.getId());
                Instant next = nextFundingTime(pair, now);
                Optional<Duration> interval = Optional.ofNullable(intervals.get(pair.getSymbol()));
                if (!FundingSettlementDue.isDue(stored.orElse(null), next, interval, now)) {
                    continue;
                }
                due++;
                Instant start = stored.map(FundingTimes::after).orElse(now.minus(properties.settlementDepth()));
                Stored result = storeSettlements(pair, start, now);
                inserted += result.inserted();
                withoutMarkPrice += result.withoutMarkPrice();
            } catch (BinanceClientException refusal) {
                if (!isDefect(refusal)) {
                    throw refusal;
                }
                log.error(
                        "NSF-04 {} funding defect, skipped this run: {}",
                        pair.getSymbol(),
                        refusal.getMessage(),
                        refusal);
                defects.add(pair.getSymbol());
            }
        }
        return new SettlementRun(targets.size(), due, inserted, withoutMarkPrice, defects);
    }

    // ------------------------------------------------------------------ metrics

    /** Brings one metric of one pair up to date; false when the run's request limit stopped it. */
    private boolean collect(CryptoPair pair, Metric metric, Instant floor, Instant cutoff, Counters counters) {
        Duration step = PERIOD.duration();
        Instant start = metric.latest(metrics, pair.getId(), floor)
                .map(stored -> stored.plus(step))
                .orElse(floor);
        Instant lastAllowed = cutoff.minusMillis(1);
        while (start.isBefore(cutoff)) {
            if (counters.requests >= properties.maxRequestsPerRun()) {
                log.info("NSF-04 stopped at {} requests; the next run continues", counters.requests);
                return false;
            }
            Instant windowEnd = start.plus(step.multipliedBy(properties.pageSize() - 1L));
            Instant end = windowEnd.isAfter(lastAllowed) ? lastAllowed : windowEnd;
            counters.requests++;
            Instant from = start;
            int written = metric == Metric.OPEN_INTEREST
                    ? writeOpenInterest(pair.getId(), within(openInterest(pair, start, end), from, cutoff))
                    : writeLongShort(pair.getId(), withinRatios(longShort(pair, start, end), from, cutoff));
            if (metric == Metric.OPEN_INTEREST) {
                counters.openInterest += written;
            } else {
                counters.longShort += written;
            }
            start = windowEnd.plus(step);
        }
        return true;
    }

    private List<OpenInterestStatistic> openInterest(CryptoPair pair, Instant start, Instant end) {
        return exchange.openInterestStatistics(pair.getSymbol(), PERIOD, start, end, properties.pageSize());
    }

    private List<LongShortRatio> longShort(CryptoPair pair, Instant start, Instant end) {
        return exchange.longShortAccountRatios(pair.getSymbol(), PERIOD, start, end, properties.pageSize());
    }

    private int writeOpenInterest(UUID pairId, List<OpenInterestStatistic> readings) {
        return readings.isEmpty() ? 0 : transaction.execute(status -> metrics.upsertOpenInterest(pairId, readings));
    }

    private int writeLongShort(UUID pairId, List<LongShortRatio> readings) {
        return readings.isEmpty() ? 0 : transaction.execute(status -> metrics.upsertLongShortRatio(pairId, readings));
    }

    private static List<OpenInterestStatistic> within(List<OpenInterestStatistic> readings, Instant from, Instant to) {
        return readings.stream()
                .filter(reading -> isWithin(reading.timestamp(), from, to))
                .toList();
    }

    private static List<LongShortRatio> withinRatios(List<LongShortRatio> readings, Instant from, Instant to) {
        return readings.stream()
                .filter(reading -> isWithin(reading.timestamp(), from, to))
                .toList();
    }

    private static boolean isWithin(Instant time, Instant from, Instant to) {
        return !time.isBefore(from) && time.isBefore(to);
    }

    /** The first 5-minute instant at or after {@code instant}. */
    static Instant alignUp(Instant instant) {
        long step = PERIOD.duration().toMillis();
        long millis = instant.toEpochMilli();
        return Instant.ofEpochMilli(Math.ceilDiv(millis, step) * step);
    }

    // ------------------------------------------------------------------ funding

    /** The interval of every symbol the exchange states one for; an unreadable answer states none. */
    private Map<String, Duration> fundingIntervals(List<String> defects) {
        Map<String, Duration> intervals = new HashMap<>();
        try {
            for (FundingInfo info : exchange.fundingInfo()) {
                intervals.put(info.symbol(), info.fundingInterval());
            }
        } catch (BinanceClientException refusal) {
            if (!isDefect(refusal)) {
                throw refusal;
            }
            log.error("NSF-04 fundingInfo defect; every pair is checked this run: {}", refusal.getMessage(), refusal);
            defects.add("fundingInfo");
        }
        return intervals;
    }

    /** The next funding time from the stream while it is fresh, otherwise from the REST source (BR-11). */
    private Instant nextFundingTime(CryptoPair pair, Instant now) {
        Instant oldest = now.minus(properties.markPriceMaxAge());
        return latest.markPrice(pair.getId())
                .filter(mark -> !mark.eventTime().isBefore(oldest))
                .map(MarkPriceMessage::nextFundingTime)
                .orElseGet(() -> exchange.premiumIndex(pair.getSymbol()).nextFundingTime());
    }

    /** Stores a pair's settled rates from {@code start} on, page by page, each page in its own transaction. */
    private Stored storeSettlements(CryptoPair pair, Instant start, Instant now) {
        int inserted = 0;
        int withoutMarkPrice = 0;
        Instant from = start;
        while (true) {
            List<FundingRate> page = exchange.fundingRates(pair.getSymbol(), from, null, FUNDING_PAGE);
            List<FundingRate> settled = page.stream()
                    .filter(rate -> !rate.fundingTime().isAfter(now))
                    .toList();
            List<FundingRate> storable =
                    settled.stream().filter(rate -> rate.markPrice() != null).toList();
            withoutMarkPrice += settled.size() - storable.size();
            if (!storable.isEmpty()) {
                inserted += transaction.execute(status -> metrics.insertFundingRates(pair.getId(), storable));
            }
            if (page.size() < FUNDING_PAGE) {
                return new Stored(inserted, withoutMarkPrice);
            }
            from = page.getLast().fundingTime().plusMillis(1);
        }
    }

    // ------------------------------------------------------------------ shared

    private List<CryptoPair> targets() {
        return pairs.findEnabledOnFutures().stream()
                .filter(pair -> pair.exchangeStatus(MarketType.FUTURES) == ExchangeStatus.TRADING)
                .toList();
    }

    private static boolean isDefect(BinanceClientException refusal) {
        return refusal.kind() == BinanceClientException.Kind.REJECTED
                || refusal.kind() == BinanceClientException.Kind.MALFORMED;
    }

    /** The two metrics, each resumed from its own latest stored instant. */
    private enum Metric {
        OPEN_INTEREST("open interest") {
            @Override
            Optional<Instant> latest(FuturesMetricsRepository metrics, UUID pairId, Instant floor) {
                return metrics.latestOpenInterest(pairId, floor);
            }
        },
        LONG_SHORT_RATIO("long/short ratio") {
            @Override
            Optional<Instant> latest(FuturesMetricsRepository metrics, UUID pairId, Instant floor) {
                return metrics.latestLongShortRatio(pairId, floor);
            }
        };

        private final String label;

        Metric(String label) {
            this.label = label;
        }

        abstract Optional<Instant> latest(FuturesMetricsRepository metrics, UUID pairId, Instant floor);
    }

    /** What storing one pair's settlements came to. */
    private record Stored(int inserted, int withoutMarkPrice) {}

    /** Running totals of one metrics collection. */
    private static final class Counters {
        private int openInterest;
        private int longShort;
        private int requests;

        MetricsRun run(boolean stopped, List<String> defects) {
            return new MetricsRun(openInterest, longShort, requests, stopped, List.copyOf(defects));
        }
    }
}
