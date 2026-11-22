package com.cryptopilot.market.job;

import com.cryptopilot.market.event.PairMarketEnabled;
import com.cryptopilot.market.service.MarketReferenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

/**
 * Refreshes CoinPaprika's figures of the market list at start-up and every {@link #EVERY}: the whole market's cap each
 * time, the coins' supplies hourly (see {@link MarketReferenceService}). About 150 calls for the cap and 24 for the
 * supplies a day, well inside the free plan. One run at a time; nothing here is thrown into the scheduler.
 *
 * <p>A pair the administrator brings into service (UC-45) may be a coin with no supply yet, which would show no market
 * cap until the next hourly read: the supplies are read again {@link #SETTLE} after the last such pair, so a batch of
 * pairs makes one read. A read that finds another run in progress is put off by {@link #SETTLE} again.
 *
 * <p>Rule: SRS 3.3.1; UC-45.
 */
public class MarketReferenceJob {

    /** How often the figures are refreshed. */
    static final Duration EVERY = Duration.ofMinutes(10);

    /** The least time between two reads of the supplies: a batch of pairs never costs more than one. */
    static final Duration SUPPLIES_FLOOR = Duration.ofMinutes(5);

    /** How long after the last pair brought into service the supplies are read. */
    static final Duration SETTLE = Duration.ofSeconds(30);

    private final MarketReferenceService reference;
    private final TaskScheduler scheduler;
    private final Clock clock;

    private final ReentrantLock running = new ReentrantLock();
    private final Object pendingLock = new Object();
    private ScheduledFuture<?> pending;

    public MarketReferenceJob(MarketReferenceService reference, TaskScheduler scheduler, Clock clock) {
        this.reference = reference;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** One run now, then one every {@link #EVERY}. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleAtFixedRate(this::run, clock.instant(), EVERY);
    }

    /** A pair came into service: read the supplies once the pairs of the batch have settled, after commit. */
    @EventListener
    public void onPairMarketEnabled(PairMarketEnabled event) {
        // AfterCommit logs a refused schedule (shutting down); the hourly read catches up.
        AfterCommit.run(() -> scheduleSupplies(clock.instant().plus(SETTLE)));
    }

    /** One refresh, unless one is in progress. */
    public void run() {
        if (!running.tryLock()) {
            return;
        }
        try {
            reference.refresh();
        } finally {
            running.unlock();
        }
    }

    /**
     * One read of the supplies; put off by {@link #SETTLE} while a run is in progress, and until
     * {@link #SUPPLIES_FLOOR} after the last read when that was sooner.
     */
    void runSupplies() {
        if (!running.tryLock()) {
            scheduleSupplies(clock.instant().plus(SETTLE));
            return;
        }
        try {
            Optional<Instant> last = reference.suppliesReadAt();
            if (last.isPresent() && last.get().plus(SUPPLIES_FLOOR).isAfter(clock.instant())) {
                scheduleSupplies(last.get().plus(SUPPLIES_FLOOR));
                return;
            }
            reference.refreshSupplies();
        } finally {
            running.unlock();
        }
    }

    private void scheduleSupplies(Instant at) {
        synchronized (pendingLock) {
            if (pending != null) {
                pending.cancel(false);
            }
            pending = scheduler.schedule(this::runSupplies, at);
        }
    }
}
