package com.cryptopilot.watchlist;

import com.cryptopilot.watchlist.model.AlertsChanged;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records the {@link AlertsChanged} events the alert engine would receive: after the commit only, as it listens.
 * {@code @RecordApplicationEvents} also sees events whose transaction rolled back.
 */
@TestComponent
public class AlertsChangedRecorder {

    private final List<AlertsChanged> received = new ArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    synchronized void on(AlertsChanged event) {
        received.add(event);
    }

    /** The events received since the last {@link #clear()}, in order. */
    public synchronized List<AlertsChanged> received() {
        return List.copyOf(received);
    }

    public synchronized void clear() {
        received.clear();
    }
}
