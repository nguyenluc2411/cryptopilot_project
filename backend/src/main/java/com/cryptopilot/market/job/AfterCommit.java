package com.cryptopilot.market.job;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a listener's work once the publishing transaction commits, and never when it rolls back; at once when there is no
 * transaction. Used instead of {@code @TransactionalEventListener}, which Spring Modulith would store in the event
 * publication registry: these announcements are fire-and-forget, the periodic refresh being their fallback.
 *
 * <p>Reference: Kleppmann, M. <i>Designing Data-Intensive Applications</i>, 2017, ch. 11 (acting on committed events).
 */
final class AfterCommit {

    private AfterCommit() {}

    static void run(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }
}
