package com.cryptopilot.market.job;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.MarketStatusService;
import com.cryptopilot.market.service.impl.MarketLiveness;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * committed, every {@link #RECOVERING_EVERY} while a market recovers, so the banner keeps showing it is still catching
 * up, and when the pairs whose data is lost on an open market change (E5). Clients that missed a push read {@code GET /api/v1/market/status}.
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
    private final MarketLiveness liveness;
    private final SimpMessageSendingOperations broker;
    private final TaskScheduler scheduler;
    private final MarketHealthProperties properties;
    private final BinanceStreamProperties streams;
    private final Map<MarketType, List<String>> lastSilent = new EnumMap<>(MarketType.class);

    public MarketStatusPusher(
            MarketStatusService status,
            MarketHealthService health,
            MarketLiveness liveness,
            SimpMessageSendingOperations broker,
            TaskScheduler scheduler,
            MarketHealthProperties properties,
            BinanceStreamProperties streams) {
        this.status = status;
        this.health = health;
        this.liveness = liveness;
        for (MarketType market : MarketType.values()) {
            lastSilent.put(market, List.of());
        }
        this.broker = broker;
        this.scheduler = scheduler;
        this.properties = properties;
        this.streams = streams;
    }

    /**
     * Schedules the pushes while a market recovers, when the monitor runs, as {@link MarketHealthMonitor} does: with the
     * monitor or the streams off, no market ever recovers.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled() || !streams.enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::pushWhileRecovering, RECOVERING_EVERY);
    }

    /** A state changed: the status after it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(MarketHealthChanged changed) {
        push();
    }

    /** One look: pushes while a market recovers, or when its lost pairs changed since the last look. */
    void pushWhileRecovering() {
        boolean due = false;
        for (MarketType market : MarketType.values()) {
            List<String> silent = liveness.silentSymbols(market);
            if (!silent.equals(lastSilent.put(market, silent))
                    || health.state(market) == MarketHealthState.RECOVERING) {
                due = true;
            }
        }
        if (due) {
            push();
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
