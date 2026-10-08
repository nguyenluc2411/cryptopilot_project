package com.cryptopilot.watchlist;

import com.cryptopilot.watchlist.event.AlertTriggered;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records the {@link AlertTriggered} events a listener of NSF-15 would receive: after the commit, from any thread.
 * {@code @RecordApplicationEvents} sees only the test thread and also events whose transaction rolled back.
 */
@TestComponent
public class AlertTriggeredRecorder {

    private final List<AlertTriggered> received = new ArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    synchronized void on(AlertTriggered event) {
        received.add(event);
        notifyAll();
    }

    /** The events received for this alert, in order. */
    public synchronized List<AlertTriggered> of(UUID alertId) {
        return received.stream()
                .filter(event -> event.alertId().equals(alertId))
                .toList();
    }

    /**
     * Waits until this alert has at least {@code count} events, woken by each event rather than polling.
     *
     * @return the events received for the alert, in order
     * @throws AssertionError when they have not arrived within {@code timeout}
     */
    public synchronized List<AlertTriggered> await(UUID alertId, int count, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<AlertTriggered> events = of(alertId);
        while (events.size() < count) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("alert " + alertId + ": " + events.size() + " of " + count
                        + " AlertTriggered events within " + timeout);
            }
            wait(Math.max(1, left / 1_000_000));
            events = of(alertId);
        }
        return events;
    }
}
