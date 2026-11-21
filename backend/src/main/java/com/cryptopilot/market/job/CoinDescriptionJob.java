package com.cryptopilot.market.job;

import com.cryptopilot.market.client.CoinPaprikaException;
import com.cryptopilot.market.event.PairMarketEnabled;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.service.CoinDescriptionService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

/**
 * Describes the coins from CoinPaprika after each symbol synchronisation (NSF-01), which is when new coins arrive: at
 * start-up and daily.
 *
 * <p>NSF-01 announces each market on its own, so one synchronisation is two events; the run is put off by
 * {@link #SETTLE} and a later event replaces a pending run, so both markets are described by one run. The listener
 * only schedules: the HTTP calls happen on the scheduler, never on the thread that synchronised the symbols.
 *
 * <p>A pair the administrator adds (UC-45) is synchronised on its own, without that event, so a pair brought into
 * service schedules the run the same way: a batch of pairs makes one run, and a run with every coin described reads
 * nothing.
 *
 * <p>Also once an hour ({@link #EVERY}), for the coins a run left without a logo because the hour's share of CoinPaprika
 * calls was spent; with every coin described, or tried within the day, such a run reads nothing.
 *
 * <p>A CoinPaprika that asks to slow down (429, 402) is tried once more after {@link #RATE_LIMIT_WAIT}; any other
 * failure waits for the next synchronisation. Either way the coins keep what they have, and nothing about prices or trading
 * depends on this job.
 *
 * <p>Rule: SRS 3.1.5; NSF-01; UC-45.
 */
public class CoinDescriptionJob {

    private static final Logger log = LoggerFactory.getLogger(CoinDescriptionJob.class);

    /** How long after the last market's synchronisation the run starts. */
    static final Duration SETTLE = Duration.ofSeconds(30);

    /** How often the coins still without a logo are described again. */
    static final Duration EVERY = Duration.ofHours(1);

    /** How long a 429 of CoinPaprika is waited out before the one retry. */
    static final Duration RATE_LIMIT_WAIT = Duration.ofMinutes(2);

    private final CoinDescriptionService descriptions;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();
    private final Object pendingLock = new Object();
    private ScheduledFuture<?> pending;

    public CoinDescriptionJob(CoinDescriptionService descriptions, TaskScheduler scheduler, Clock clock) {
        this.descriptions = descriptions;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** The hourly run, the first an hour from now: the start-up synchronisation describes the coins before. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleAtFixedRate(() -> run(false), clock.instant().plus(EVERY), EVERY);
    }

    /** A market was synchronised: describe the coins once both have settled. */
    @EventListener
    public void onSymbolsSynchronised(SymbolsSynchronised event) {
        schedule(SETTLE, true);
    }

    /** A pair came into service: describe its coin once the pairs of the batch have settled, after commit. */
    @EventListener
    public void onPairMarketEnabled(PairMarketEnabled event) {
        // AfterCommit logs a refused schedule (shutting down); the hourly run catches up.
        AfterCommit.run(() -> schedule(SETTLE, true));
    }

    /**
     * One run now, on the calling thread.
     *
     * @param retryOnRateLimit whether a 429 schedules one more run
     * @return how many coins changed, or -1 when the run failed or another run was in progress
     */
    public int run(boolean retryOnRateLimit) {
        if (!running.tryLock()) {
            return -1;
        }
        try {
            return descriptions.describeCoins();
        } catch (CoinPaprikaException failure) {
            if (failure.rateLimited() && retryOnRateLimit) {
                log.warn("Coin descriptions: CoinPaprika rate limited; one retry in {}", RATE_LIMIT_WAIT);
                schedule(RATE_LIMIT_WAIT, false);
            } else {
                log.warn(
                        "Coin descriptions: CoinPaprika failed ({}); the next synchronisation tries again",
                        failure.getMessage());
            }
            return -1;
        } catch (RuntimeException unexpected) {
            log.error("Coin descriptions failed unexpectedly; the next synchronisation tries again", unexpected);
            return -1;
        } finally {
            running.unlock();
        }
    }

    private void schedule(Duration after, boolean retryOnRateLimit) {
        synchronized (pendingLock) {
            if (pending != null) {
                pending.cancel(false);
            }
            pending = scheduler.schedule(
                    () -> run(retryOnRateLimit), clock.instant().plus(after));
        }
    }
}
