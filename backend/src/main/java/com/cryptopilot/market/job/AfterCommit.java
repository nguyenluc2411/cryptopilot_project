package com.cryptopilot.market.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a listener's work once the publishing transaction commits, and never when it rolls back; at once when there is no
 * transaction. Used instead of {@code @TransactionalEventListener}, which Spring Modulith would store in the event
 * publication registry: these announcements are fire-and-forget, the periodic refresh being their fallback.
 *
 * <p>The work never throws to the caller: an exception from {@code afterCommit()} would reach whoever committed, and
 * answer a saved change with a 500.
 *
 * <p>Reference: Kleppmann, M. <i>Designing Data-Intensive Applications</i>, 2017, ch. 11 (acting on committed events);
 * Spring Framework reference, {@code TransactionSynchronization#afterCommit} (exceptions propagate to the caller).
 */
final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    static void run(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            guarded(work);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                guarded(work);
            }
        });
    }

    private static void guarded(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            // The periodic refresh and backfill catch up.
            log.warn("After-commit work failed; the next scheduled run catches up: {}", failure.toString());
        }
    }
}
