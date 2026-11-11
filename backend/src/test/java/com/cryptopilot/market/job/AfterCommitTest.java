package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** After-commit work runs only on commit, and a failure of it never reaches whoever committed. */
class AfterCommitTest {

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void workThatThrowsAfterCommit_doesNotReachTheCaller() {
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(() -> {
            throw new TaskRejectedException("shutting down");
        });

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
    }

    @Test
    void workThatThrowsWithoutATransaction_doesNotReachTheCaller() {
        assertThatCode(() -> AfterCommit.run(() -> {
                    throw new TaskRejectedException("shutting down");
                }))
                .doesNotThrowAnyException();
    }

    @Test
    void workOfARolledBackTransaction_neverRuns() {
        AtomicInteger runs = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(runs::incrementAndGet);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(runs).hasValue(0);
    }
}
