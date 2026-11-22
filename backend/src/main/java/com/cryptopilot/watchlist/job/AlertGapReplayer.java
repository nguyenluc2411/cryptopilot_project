package com.cryptopilot.watchlist.job;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.calculator.PriceAlertBook.ReplayedPrice;
import com.cryptopilot.watchlist.config.AlertReplayProperties;
import com.cryptopilot.watchlist.job.AlertEngine.PairKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * T-111 (D-87): replays the 1-minute prices PRICE alerts missed, so a cross or a level reached while nobody listened
 * still fires, late, instead of never.
 *
 * <ul>
 *   <li><b>Lost stream.</b> When a market's stream reconnects, each pair whose book holds alerts is replayed from the
 *       newest price it received before the loss up to now.
 *   <li><b>Restart.</b> Once the application is ready, after {@code startDelay}, each such pair is replayed over the
 *       last {@code window}: what happened while the application was down. A trigger recorded before the restart is
 *       not repeated, since a replayed price only fires an alert if it came after the alert's last trigger.
 *   <li><b>Stale prices are skipped, explicitly.</b> A replay reaches back at most {@code window} (15 minutes by
 *       default); an older missed price is not fired late, and the skip is logged. The first replayed candle only
 *       seeds the path, so no cross is measured from a price older than the window.
 *   <li><b>Fetched, not streamed.</b> The closed 1-minute candles come from the exchange through
 *       {@link MarketApi#closedMinuteKlines}; their closes, the last traded prices, are what a live evaluation sees.
 *       A refusal of the exchange ends that pair's replay with a warning; the stream already carries the live prices.
 *   <li><b>Off the stream threads.</b> Each replay runs on its own virtual thread; one replay per market at a time,
 *       and a reconnection during one runs again once it ends.
 * </ul>
 *
 * <p>INDICATOR alerts are not replayed, on purpose: they are evaluated on the indicators of a closed 15m, 1h, 4h or 1d
 * candle (NSF-05), and the next candle that closes after the gap is evaluated on its own indicators. A candle that
 * closed during the gap is backfilled by NSF-02 without its indicators being pushed again, so its condition is not
 * fired late (T-111 decision, recorded with the task).
 *
 * <p>Rule: NSF-06, NSF-02, BR-19, BR-20; TECHNICAL_DESIGN 7.9; D-87.
 */
@Component
public class AlertGapReplayer {

    private static final Logger log = LoggerFactory.getLogger(AlertGapReplayer.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final AlertEngine engine;
    private final MarketApi market;
    private final AlertReplayProperties properties;
    private final Clock clock;
    private final ThreadFactory threads;

    /** The markets with a replay running, and those asked for again meanwhile; guarded by {@code this}. */
    private final Set<MarketType> busy = EnumSet.noneOf(MarketType.class);

    private final Set<MarketType> again = EnumSet.noneOf(MarketType.class);

    @Autowired
    public AlertGapReplayer(AlertEngine engine, MarketApi market, AlertReplayProperties properties, Clock clock) {
        this(
                engine,
                market,
                properties,
                clock,
                Thread.ofVirtual().name("watchlist-alert-replay").factory());
    }

    AlertGapReplayer(
            AlertEngine engine,
            MarketApi market,
            AlertReplayProperties properties,
            Clock clock,
            ThreadFactory threads) {
        this.engine = engine;
        this.market = market;
        this.properties = properties;
        this.clock = clock;
        this.threads = threads;
    }

    /** After start-up: replays the window the application was down for, once the engine has loaded its alerts. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        if (!properties.enabled()) {
            return;
        }
        threads.newThread(() -> {
                    try {
                        Thread.sleep(properties.startDelay());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (MarketType each : MarketType.values()) {
                        replayMarket(each, true);
                    }
                })
                .start();
    }

    /** A market's stream came back: replays what its pairs missed while it was lost. */
    @EventListener
    public void onStreamReconnected(MarketStreamReconnected event) {
        if (properties.enabled()) {
            threads.newThread(() -> replayMarket(event.market(), false)).start();
        }
    }

    /**
     * Replays one market's pairs, unless a replay of it is already running, in which case that one runs again when it
     * ends.
     *
     * @param restart whether the gap is the restart's, the whole window, rather than the newest price onwards
     */
    void replayMarket(MarketType target, boolean restart) {
        synchronized (this) {
            if (!busy.add(target)) {
                again.add(target);
                return;
            }
        }
        boolean done = false;
        try {
            do {
                replayOnce(target, restart);
            } while (askedAgain(target));
            done = true;
        } finally {
            if (!done) {
                synchronized (this) {
                    busy.remove(target);
                    again.remove(target);
                }
            }
        }
    }

    /** Whether another replay of the market was asked for; if not, the market is no longer busy. */
    private synchronized boolean askedAgain(MarketType target) {
        if (again.remove(target)) {
            return true;
        }
        busy.remove(target);
        return false;
    }

    private void replayOnce(MarketType target, boolean restart) {
        if (!engine.isRunning()) {
            return;
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        engine.replayTargets(target).forEach((key, lastSeen) -> {
            try {
                replayPair(key, gapStart(key, restart ? Optional.empty() : lastSeen, windowStart), now);
            } catch (RuntimeException failure) {
                log.warn(
                        "NSF-06 {} {}: the missed prices were not replayed: {}",
                        key.market(),
                        key.pairId(),
                        failure.toString());
            }
        });
    }

    /** Where the replay of one pair begins: its newest price, or the window's start when that is older or unknown. */
    private Instant gapStart(PairKey key, Optional<Instant> lastSeen, Instant windowStart) {
        if (lastSeen.isPresent() && lastSeen.get().isBefore(windowStart)) {
            log.info(
                    "NSF-06 {} {}: prices between {} and {} are older than the replay window and are skipped",
                    key.market(),
                    key.pairId(),
                    lastSeen.get(),
                    windowStart);
        }
        Instant from = lastSeen.filter(seen -> !seen.isBefore(windowStart)).orElse(windowStart);
        return from.truncatedTo(ChronoUnit.MINUTES);
    }

    private void replayPair(PairKey key, Instant from, Instant now) {
        List<ReplayedPrice> path = new ArrayList<>();
        Instant cursor = from;
        for (int page = 0; page < properties.maxPages() && cursor.isBefore(now); page++) {
            MinuteKlineBatch batch = market.closedMinuteKlines(key.market(), key.pairId(), cursor);
            if (batch.refused()) {
                log.warn(
                        "NSF-06 {} {}: the exchange refused the missed candles until {}; replaying what was fetched",
                        key.market(),
                        key.pairId(),
                        batch.retryAt());
                break;
            }
            if (batch.klines().isEmpty()) {
                break;
            }
            for (MinuteKline kline : batch.klines()) {
                if (kline.closed()
                        && !kline.openTime().isBefore(from)
                        && kline.eventTime().isBefore(now)) {
                    path.add(new ReplayedPrice(kline.close(), kline.eventTime()));
                }
            }
            cursor = batch.klines().getLast().openTime().plus(MINUTE);
        }
        if (path.size() > 1) {
            engine.replay(key.market(), key.pairId(), path);
        }
    }
}
