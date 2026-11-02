package com.cryptopilot.paper.job;

import com.cryptopilot.paper.config.PaperPairStopSweepProperties;
import com.cryptopilot.paper.service.impl.FuturesPairStop;
import com.cryptopilot.paper.service.impl.PairStopCancellation;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Cancels, every {@code interval}, the Spot paper orders still open on a pair the system no longer trades: the exchange
 * stopped trading it (NSF-01) or an administrator disabled it (BR-07). {@link PairStopListener} cancels them when the
 * stop or the disabling commits, but it reads the accounts without their locks: an order whose placement read the
 * pair as trading before that committed, and committed after that read, is missed there. This run finds it from the stored state and cancels it through the same {@link PairStopCancellation}, each
 * account in its own transaction under its lock. Run again, or by a second instance, it cancels nothing twice.
 *
 * <p>The paper Futures of a pair out of service are ended the same way ({@link FuturesPairStop}): orders cancelled,
 * positions closed at the mark (TR-46); a pair without a mark yet is tried again next run.
 *
 * <p>Rule: TR-02, TR-46; NSF-01; BR-07.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew) and
 * ch. 11 (derived state reconciled from the source of truth).
 */
@Component
public class PaperPairStopSweep {

    private static final Logger log = LoggerFactory.getLogger(PaperPairStopSweep.class);

    private final PairStopCancellation cancellation;
    private final FuturesPairStop futures;
    private final TaskScheduler scheduler;
    private final PaperPairStopSweepProperties properties;

    public PaperPairStopSweep(
            PairStopCancellation cancellation,
            FuturesPairStop futures,
            TaskScheduler scheduler,
            PaperPairStopSweepProperties properties) {
        this.cancellation = cancellation;
        this.futures = futures;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the runs, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-01 paper pair-stop sweep is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info("NSF-01 paper pair-stop sweep every {}", properties.interval());
    }

    /**
     * One run; an account that fails is logged and left for the next run, and does not stop the others.
     *
     * @return how many orders it cancelled
     */
    public int run() {
        int cancelled = 0;
        try {
            // Only the pairs with open orders, which are few, rather than every pair out of service.
            for (UUID pairId : cancellation.pairsWithOrders()) {
                if (!cancellation.outOfService(pairId)) {
                    continue;
                }
                for (UUID userId : cancellation.accountsWithOrders(pairId)) {
                    cancelled += cancelOne(userId, pairId);
                }
            }
            for (UUID pairId : futures.pairsInUse()) {
                if (!futures.outOfService(pairId)) {
                    continue;
                }
                for (UUID userId : futures.accountsOn(pairId)) {
                    cancelled += stopFutures(userId, pairId);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("NSF-01 paper pair-stop sweep failed: {}", failure.toString());
        }
        if (cancelled > 0) {
            log.warn("NSF-01 paper pair-stop sweep cancelled {} orders the pair stop had missed", cancelled);
        }
        return cancelled;
    }

    private int stopFutures(UUID userId, UUID pairId) {
        try {
            return futures.stop(userId, pairId);
        } catch (RuntimeException failure) {
            log.warn(
                    "TR-46 paper Futures of account {} on pair {} not ended; tried again next run: {}",
                    userId,
                    pairId,
                    failure.toString());
            return 0;
        }
    }

    private int cancelOne(UUID userId, UUID pairId) {
        try {
            return cancellation.cancel(userId, pairId);
        } catch (RuntimeException failure) {
            log.warn(
                    "NSF-01 paper orders of account {} on pair {} not cancelled; tried again next run: {}",
                    userId,
                    pairId,
                    failure.toString());
            return 0;
        }
    }
}
