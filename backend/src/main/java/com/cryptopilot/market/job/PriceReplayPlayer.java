package com.cryptopilot.market.job;

import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.AggTradeMessage;
import com.cryptopilot.market.client.StreamMessage.BookTickerMessage;
import com.cryptopilot.market.client.StreamMessage.DepthMessage;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.Level;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.ReplayScenario;
import com.cryptopilot.market.model.ReplayStatus;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.impl.LatestMarketData;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Plays price replays (RF-07): every step of a running {@link ReplayScenario}, the pair's price on its path is sent
 * through {@link MarketStreamSupervisor#replay} as the exchange's own streams would send it, so every engine reads it
 * the way it reads a live price.
 *
 * <ul>
 *   <li>Both markets: a trade at the price, a best bid and ask {@code spread} apart around it, a five-level depth and
 *       the forming 1-minute candle; when the minute turns, the previous one is sent closed first. A 1-minute candle
 *       is never stored, so the replay leaves the stored history untouched (BR-08).
 *   <li>Futures: the mark and index price, with the funding the exchange last sent; Spot: the ticker, with the
 *       exchange's last 24-hour figures widened to the price.
 *   <li>Starting a scenario on a pair that is replayed already replaces the scenario and
 *       keeps the minute's candle, from the new scenario's first price; stopping it gives the pair the exchange's prices back.
 * </ul>
 *
 * <p>Rule: RF-07 (Review 2 commitment, BM04 section 9); NSF-03; BR-08.
 */
@Component
public class PriceReplayPlayer {

    private static final Logger log = LoggerFactory.getLogger(PriceReplayPlayer.class);
    private static final BigDecimal QUANTITY = new BigDecimal("1");
    private static final BigDecimal DEPTH_QUANTITY = new BigDecimal("1000");
    private static final int DEPTH_LEVELS = 5;
    private static final Duration FUNDING_EVERY = Duration.ofHours(8);

    private final MarketStreamSupervisor supervisor;
    private final LatestMarketData latest;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Map<Key, Run> runs = new ConcurrentHashMap<>();

    public PriceReplayPlayer(
            MarketStreamSupervisor supervisor, LatestMarketData latest, TaskScheduler scheduler, Clock clock) {
        this.supervisor = supervisor;
        this.latest = latest;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /**
     * Starts the scenario on its pair, replacing the one running there; its first price is sent at once.
     *
     * @return the replay, or empty when its market does not stream the pair now
     */
    public synchronized Optional<ReplayStatus> start(ReplayScenario scenario) {
        Key key = new Key(scenario.market(), scenario.symbol());
        Optional<StreamTarget> target = supervisor.beginReplay(scenario.market(), scenario.symbol());
        if (target.isEmpty()) {
            return Optional.empty();
        }
        Run previous = runs.remove(key);
        if (previous != null) {
            previous.future.cancel(false);
        }
        Run run = new Run(scenario, target.get(), clock.instant(), previous == null ? null : previous.candle);
        runs.put(key, run);
        run.step();
        run.future = scheduler.scheduleAtFixedRate(run::stepSafely, scenario.step());
        log.warn("RF-07 {} {} replays {}", scenario.market(), scenario.symbol(), scenario.name());
        return Optional.of(run.status());
    }

    /**
     * Stops the replay of a pair, which takes the exchange's prices again.
     *
     * @return whether one was running
     */
    public synchronized boolean stop(MarketType market, String symbol) {
        Run run = runs.remove(new Key(market, symbol));
        if (run == null) {
            return false;
        }
        run.future.cancel(false);
        supervisor.endReplay(market, symbol);
        log.warn("RF-07 {} {} back to the exchange's prices", market, symbol);
        return true;
    }

    /** The replays running now, by market and pair. */
    public List<ReplayStatus> running() {
        List<ReplayStatus> statuses = new ArrayList<>();
        runs.values().forEach(run -> statuses.add(run.status()));
        statuses.sort(Comparator.comparing(ReplayStatus::market).thenComparing(ReplayStatus::symbol));
        return statuses;
    }

    /** Stops every replay. */
    @PreDestroy
    public synchronized void stopAll() {
        List.copyOf(runs.keySet()).forEach(key -> stop(key.market(), key.symbol()));
    }

    private record Key(MarketType market, String symbol) {}

    /** The candle of the minute being replayed. */
    private record Candle(
            Instant openTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            long trades) {

        Candle with(BigDecimal price) {
            return new Candle(openTime, open, high.max(price), low.min(price), price, volume.add(QUANTITY), trades + 1);
        }

        Kline kline() {
            return new Kline(
                    openTime,
                    openTime.plus(Duration.ofMinutes(1)).minusMillis(1),
                    open,
                    high,
                    low,
                    close,
                    volume,
                    volume.multiply(close),
                    trades);
        }
    }

    /** One running replay; stepped on the scheduler's threads, one step at a time. */
    private final class Run {

        private final ReplayScenario scenario;
        private final StreamTarget target;
        private final Instant startedAt;
        private Candle candle;
        private volatile BigDecimal price;
        private long tradeId;
        private long updateId;
        private ScheduledFuture<?> future;

        private Run(ReplayScenario scenario, StreamTarget target, Instant startedAt, Candle candle) {
            this.scenario = scenario;
            this.target = target;
            this.startedAt = startedAt;
            this.candle = candle;
            this.price = scenario.priceAt(Duration.ZERO);
        }

        ReplayStatus status() {
            return new ReplayStatus(
                    scenario.name(),
                    scenario.market(),
                    scenario.symbol(),
                    startedAt,
                    startedAt.plus(scenario.length()),
                    price);
        }

        /** A failed step is logged and the next one runs: a throw would cancel the schedule. */
        void stepSafely() {
            try {
                step();
            } catch (RuntimeException failure) {
                log.warn("RF-07 {} step failed: {}", scenario.name(), failure.toString());
            }
        }

        synchronized void step() {
            Instant now = clock.instant();
            BigDecimal next = scenario.priceAt(Duration.between(startedAt, now));
            price = next;
            MarketType market = scenario.market();
            Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
            if (candle != null && candle.openTime().isBefore(minute)) {
                send(new KlineMessage(target.symbol(), MarketInterval.ONE_MINUTE, candle.kline(), true, now));
                candle = null;
            }
            candle = (candle == null ? new Candle(minute, next, next, next, next, BigDecimal.ZERO, 0) : candle)
                    .with(next);
            BigDecimal half = scenario.spread().divide(BigDecimal.TWO);
            BigDecimal bid = next.subtract(half);
            BigDecimal ask = next.add(half);
            send(new AggTradeMessage(target.symbol(), ++tradeId, next, QUANTITY, now, false, now));
            send(new BookTickerMessage(target.symbol(), ++updateId, bid, DEPTH_QUANTITY, ask, DEPTH_QUANTITY, now));
            send(new DepthMessage(target.symbol(), updateId, levels(bid, half.negate()), levels(ask, half), now));
            send(new KlineMessage(target.symbol(), MarketInterval.ONE_MINUTE, candle.kline(), false, now));
            if (market == MarketType.FUTURES) {
                send(mark(next, now));
            } else {
                send(ticker(next, bid, ask, now));
            }
        }

        private void send(StreamMessage message) {
            supervisor.replay(scenario.market(), message);
        }

        private MarkPriceMessage mark(BigDecimal at, Instant now) {
            Optional<MarkPriceMessage> live = latest.markPrice(target.pairId());
            BigDecimal funding = live.map(MarkPriceMessage::fundingRate).orElse(BigDecimal.ZERO);
            Instant nextFunding = live.map(MarkPriceMessage::nextFundingTime)
                    .filter(time -> time.isAfter(now))
                    .orElseGet(() -> nextFunding(now));
            return new MarkPriceMessage(target.symbol(), at, at, funding, nextFunding, now);
        }

        private TickerMessage ticker(BigDecimal at, BigDecimal bid, BigDecimal ask, Instant now) {
            Optional<TickerMessage> live = latest.ticker(target.pairId());
            BigDecimal high = live.map(TickerMessage::highPrice24h).map(at::max).orElse(at);
            BigDecimal low = live.map(TickerMessage::lowPrice24h).map(at::min).orElse(at);
            return new TickerMessage(
                    target.symbol(),
                    at,
                    bid,
                    ask,
                    high,
                    low,
                    live.map(TickerMessage::priceChangePercent24h).orElse(BigDecimal.ZERO),
                    live.map(TickerMessage::baseVolume24h).orElse(BigDecimal.ZERO),
                    live.map(TickerMessage::quoteVolume24h).orElse(BigDecimal.ZERO),
                    now);
        }

        private static List<Level> levels(BigDecimal best, BigDecimal step) {
            List<Level> levels = new ArrayList<>();
            for (int i = 0; i < DEPTH_LEVELS; i++) {
                levels.add(new Level(best.add(step.multiply(BigDecimal.valueOf(i))), DEPTH_QUANTITY));
            }
            return levels;
        }

        private static Instant nextFunding(Instant now) {
            Instant day = now.truncatedTo(ChronoUnit.DAYS);
            Instant next = day;
            while (!next.isAfter(now)) {
                next = next.plus(FUNDING_EVERY);
            }
            return next;
        }
    }
}
