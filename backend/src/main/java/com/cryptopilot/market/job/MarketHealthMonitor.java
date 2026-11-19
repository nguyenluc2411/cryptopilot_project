package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketRecoveryCheck;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.RestOutage;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.impl.MarketLiveness;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Watches the data health of each market every {@link #INTERVAL} (D-158): reads when its stream last delivered and
 * whether its REST API is banned or behind an open circuit, asks the modules whether they have caught up while it
 * recovers, and hands the signals to {@link MarketHealthService#evaluate}.
 *
 * <p>Runs only while both the monitor and the streams are enabled: without streams every market would look lost.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Component
public class MarketHealthMonitor {

    /** How often each market is looked at. */
    static final Duration INTERVAL = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(MarketHealthMonitor.class);

    private final MarketHealthService health;
    private final MarketLiveness liveness;
    private final BinanceRestClient exchange;
    private final List<MarketRecoveryCheck> checks;
    private final MarketHealthProperties properties;
    private final BinanceStreamProperties streams;
    private final TaskScheduler scheduler;

    public MarketHealthMonitor(
            MarketHealthService health,
            MarketLiveness liveness,
            BinanceRestClient exchange,
            List<MarketRecoveryCheck> checks,
            MarketHealthProperties properties,
            BinanceStreamProperties streams,
            TaskScheduler scheduler) {
        this.health = health;
        this.liveness = liveness;
        this.exchange = exchange;
        this.checks = List.copyOf(checks);
        this.properties = properties;
        this.streams = streams;
        this.scheduler = scheduler;
    }

    /** Schedules the ticks while the monitor and the streams are enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        if (!streams.enabled()) {
            log.warn("D-158 the market health monitor is on but the streams are off; it does not run");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tick, INTERVAL);
        log.info("D-158 market health watched every {}", INTERVAL);
    }

    /** One look at every market; one that fails does not stop the other. */
    void tick() {
        for (MarketType market : MarketType.values()) {
            try {
                evaluate(market);
            } catch (RuntimeException failure) {
                log.warn("D-158 {} health not evaluated now: {}", market, failure.toString());
            }
        }
    }

    private void evaluate(MarketType market) {
        Optional<RestOutage> outage = exchange.outage(venue(market));
        boolean recovered = health.state(market) == MarketHealthState.RECOVERING && caughtUp(market);
        HealthSignals signals = new HealthSignals(
                liveness.lastLiveAt(market), liveness.liveSince(market), outage.isEmpty(), false, recovered);
        health.evaluate(market, signals, reason(outage));
    }

    private boolean caughtUp(MarketType market) {
        Instant lastLiveAt =
                health.openIncident(market).map(MarketIncident::lastLiveAt).orElse(null);
        if (lastLiveAt == null) {
            return true;
        }
        for (MarketRecoveryCheck check : checks) {
            if (!check.caughtUp(market, lastLiveAt)) {
                return false;
            }
        }
        return true;
    }

    private static IncidentReason reason(Optional<RestOutage> outage) {
        return outage.map(found -> found.kind() == BinanceClientException.Kind.BANNED
                        ? IncidentReason.IP_BANNED
                        : IncidentReason.REST_UNAVAILABLE)
                .orElse(IncidentReason.STREAM_LOST);
    }

    private static BinanceVenue venue(MarketType market) {
        return market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
    }
}
