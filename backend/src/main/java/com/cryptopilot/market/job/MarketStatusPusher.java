package com.cryptopilot.market.job;

import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.MarketStatusService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes the markets' data health to {@link #TOPIC} (D-158): whenever a market's state changes, once the change is
 * committed, and every {@link #RECOVERING_EVERY} while a market recovers, so the banner keeps showing it is still
 * catching up. Clients that missed a push read {@code GET /api/v1/market/status}.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Component
public class MarketStatusPusher {

    /** The public destination of the status. */
    public static final String TOPIC = "/topic/market.status";

    /** How often the status is pushed again while a market recovers. */
    static final Duration RECOVERING_EVERY = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(MarketStatusPusher.class);

    private final MarketStatusService status;
    private final MarketHealthService health;
    private final SimpMessageSendingOperations broker;
    private final TaskScheduler scheduler;

    public MarketStatusPusher(
            MarketStatusService status,
            MarketHealthService health,
            SimpMessageSendingOperations broker,
            TaskScheduler scheduler) {
        this.status = status;
        this.health = health;
        this.broker = broker;
        this.scheduler = scheduler;
    }

    /** Schedules the pushes while a market recovers. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::pushWhileRecovering, RECOVERING_EVERY);
    }

    /** A state changed: the status after it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(MarketHealthChanged changed) {
        push();
    }

    void pushWhileRecovering() {
        for (MarketType market : MarketType.values()) {
            if (health.state(market) == MarketHealthState.RECOVERING) {
                push();
                return;
            }
        }
    }

    private void push() {
        try {
            broker.convertAndSend(TOPIC, status.status());
        } catch (RuntimeException failure) {
            log.warn("D-158 market status not pushed: {}", failure.toString());
        }
    }
}
