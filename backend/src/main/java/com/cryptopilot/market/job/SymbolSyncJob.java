package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.config.SymbolSyncProperties;
import com.cryptopilot.market.service.SymbolSyncService;
import com.cryptopilot.market.service.SyncReport;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs NSF-01: once when the application is ready and then on the configured cron, every market on its own.
 *
 * <h2>What a refusal of the exchange does</h2>
 *
 * <p>This job is the first caller of the Binance client's caller contract, and it honours it literally:
 *
 * <ul>
 *   <li>{@code RATE_LIMITED}, {@code BANNED}, {@code CIRCUIT_OPEN} — the run of that market ends, and one run
 *       of that market is scheduled at the refusal's {@code retryAt}. Only one retry is pending per market; a
 *       new refusal replaces it.
 *   <li>{@code UNAVAILABLE} — the run ends; the next scheduled run tries again. The client already retried.
 *   <li>{@code REJECTED}, {@code MALFORMED} — logged at ERROR as a defect; waiting will not fix it.
 * </ul>
 *
 * <p>The scheduler's thread is never held: nothing here sleeps or loops on a refusal. A later attempt is a
 * new task handed to the {@link TaskScheduler} for an instant, and the thread goes back at once.
 *
 * <p>The two markets are independent: each has its own call, its own transaction and its own outcome, so a
 * futures failure never undoes or delays a Spot synchronisation. An unexpected failure — the database, say —
 * is logged and waits for the next run rather than escaping into the scheduler, which would stop nothing but
 * hide the cause.
 *
 * <p>Rule: NSF-01; BR-09; TECHNICAL_DESIGN 7.1.2 (the caller contract) and 10.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 ("Fail Fast";
 * a caller that honours a circuit breaker stops calling and comes back later rather than waiting in place).
 */
@Component
public class SymbolSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SymbolSyncJob.class);

    /** What one run of one market came to. */
    public enum Outcome {

        /** The market was synchronised. */
        SYNCED,

        /** The exchange refused with a known instant; one run was scheduled at it. */
        RETRY_SCHEDULED,

        /** The exchange was unavailable, or something unexpected failed; the next scheduled run tries again. */
        WAIT_FOR_NEXT_RUN,

        /** The request or the response is wrong; logged as a defect. */
        DEFECT
    }

    private final SymbolSyncService sync;
    private final TaskScheduler scheduler;
    private final SymbolSyncProperties properties;
    private final Clock clock;
    private final Map<MarketType, ScheduledFuture<?>> pendingRetries = new EnumMap<>(MarketType.class);

    /**
     * One run at a time, so a retry and the daily run never write the same rows together. A lock rather
     * than {@code synchronized}, which would pin a virtual thread to its carrier for the whole HTTP call.
     */
    private final ReentrantLock running = new ReentrantLock();

    public SymbolSyncJob(
            SymbolSyncService sync, TaskScheduler scheduler, SymbolSyncProperties properties, Clock clock) {
        this.sync = sync;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * When the application is ready: one run now (NSF-01, "at start-up") and the daily schedule. Nothing at
     * all when the job is disabled, as it is in any context without the {@code dev} or {@code prod} profile.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-01 symbol synchronisation is disabled");
            return;
        }
        scheduler.schedule(this::runAll, clock.instant());
        scheduler.schedule(this::runAll, new CronTrigger(properties.cron(), properties.zone()));
        log.info("NSF-01 scheduled at start-up and on '{}' ({})", properties.cron(), properties.zone());
    }

    /** One run of both markets, each on its own. */
    public void runAll() {
        for (MarketType market : MarketType.values()) {
            run(market);
        }
    }

    /** One run of one market, and what it came to. */
    public Outcome run(MarketType market) {
        running.lock();
        try {
            SyncReport report = sync.sync(market);
            log.info("NSF-01 {} synchronised: {} pairs", market, report.reconciled());
            return Outcome.SYNCED;
        } catch (BinanceClientException refusal) {
            return onRefusal(market, refusal);
        } catch (RuntimeException unexpected) {
            log.error("NSF-01 {} failed unexpectedly; the next scheduled run will try again", market, unexpected);
            return Outcome.WAIT_FOR_NEXT_RUN;
        } finally {
            running.unlock();
        }
    }

    private Outcome onRefusal(MarketType market, BinanceClientException refusal) {
        return switch (refusal.kind()) {
            case RATE_LIMITED, BANNED, CIRCUIT_OPEN -> {
                Instant at = refusal.retryAt().orElseGet(clock::instant);
                scheduleRetry(market, at);
                log.warn("NSF-01 {} refused ({}); retrying at {}", market, refusal.kind(), at);
                yield Outcome.RETRY_SCHEDULED;
            }
            case UNAVAILABLE -> {
                log.warn("NSF-01 {} exchange unavailable; the next scheduled run will try again", market, refusal);
                yield Outcome.WAIT_FOR_NEXT_RUN;
            }
            case REJECTED, MALFORMED -> {
                log.error(
                        "NSF-01 {} defect: the exchange answered {} — {}",
                        market,
                        refusal.kind(),
                        refusal.getMessage(),
                        refusal);
                yield Outcome.DEFECT;
            }
        };
    }

    private void scheduleRetry(MarketType market, Instant at) {
        ScheduledFuture<?> previous = pendingRetries.get(market);
        if (previous != null) {
            previous.cancel(false);
        }
        pendingRetries.put(market, scheduler.schedule(() -> run(market), at));
    }
}
