package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.event.MarketRecoveryStarted;
import com.cryptopilot.market.event.MarketTradingPaused;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.MarketIncidentRepository;
import com.cryptopilot.market.service.MarketHealthService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data health of each market (D-158): the current state in memory, each closed period in
 * {@code market_incident}, and an event when trading closes, when recovery starts and when trading reopens.
 *
 * <ul>
 *   <li>At start, a market with an open incident starts closed (E1): under maintenance if that is what the incident
 *       is, paused otherwise, never live before it has recovered.
 *   <li>The monitor's tick moves a market as {@link MarketHealthMachine} decides. Closing opens an incident and
 *       publishes {@link MarketTradingPaused}; a relapse while recovering stays in it, unannounced (E3); recovery
 *       publishes {@link MarketRecoveryStarted}; reopening closes the incident and publishes
 *       {@link MarketTradingResumed}.
 *   <li>Maintenance opens an incident, or takes over the open outage (E13), and holds the market until it is ended,
 *       whatever the data does. Ending it reopens the market through recovery while the monitor runs; with the
 *       monitor off, as in tests, at once, as there is nothing to recover.
 * </ul>
 *
 * <p>The changes are written and published in one transaction, so the listeners of other modules hear them after
 * the incident is stored.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Service
public class MarketHealthServiceImpl implements MarketHealthService {

    private static final Logger log = LoggerFactory.getLogger(MarketHealthServiceImpl.class);

    private final MarketHealthProperties properties;
    private final MarketHealthMachine machine;
    private final MarketIncidentRepository incidents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<MarketType, Status> statuses = new EnumMap<>(MarketType.class);

    public MarketHealthServiceImpl(
            MarketHealthProperties properties,
            MarketIncidentRepository incidents,
            ApplicationEventPublisher events,
            Clock clock) {
        this.properties = properties;
        this.machine = new MarketHealthMachine(properties);
        this.incidents = incidents;
        this.events = events;
        this.clock = clock;
        for (MarketType market : MarketType.values()) {
            statuses.put(market, Status.LIVE);
        }
        for (MarketIncident open : incidents.findAllOpen()) {
            MarketHealthState state =
                    open.kind() == IncidentKind.MAINTENANCE ? MarketHealthState.MAINTENANCE : MarketHealthState.PAUSED;
            statuses.put(open.market(), new Status(state, open.id(), open.lastLiveAt()));
            log.warn("D-158 {} starts {}: incident {} open since {}", open.market(), state, open.id(), open.pausedAt());
        }
    }

    @Override
    public synchronized MarketHealthState state(MarketType market) {
        return statuses.get(market).state();
    }

    @Override
    public void requireTrading(MarketType market) {
        Status current;
        synchronized (this) {
            current = statuses.get(market);
        }
        if (!current.state().tradingOpen()) {
            throw new TradingPausedException(market, current.state(), current.since());
        }
    }

    @Override
    public Optional<MarketIncident> openIncident(MarketType market) {
        return incidents.findOpen(market);
    }

    @Override
    @Transactional
    public synchronized void startMaintenance(MarketType market, String message, UUID by) {
        Instant now = clock.instant();
        Status current = statuses.get(market);
        if (current.incidentId() == null) {
            UUID id = UuidV7.next();
            incidents.open(new MarketIncident(
                    id,
                    market,
                    IncidentKind.MAINTENANCE,
                    IncidentReason.MAINTENANCE,
                    now,
                    now,
                    null,
                    null,
                    message,
                    by));
            statuses.put(market, new Status(MarketHealthState.MAINTENANCE, id, now));
            events.publishEvent(new MarketTradingPaused(
                    market, id, MarketHealthState.MAINTENANCE, IncidentReason.MAINTENANCE, now));
        } else {
            incidents.toMaintenance(current.incidentId(), message, by);
            statuses.put(market, new Status(MarketHealthState.MAINTENANCE, current.incidentId(), current.since()));
        }
        log.warn("D-158 {} under maintenance", market);
        changed(market, current.state(), MarketHealthState.MAINTENANCE);
    }

    @Override
    @Transactional
    public synchronized void endMaintenance(MarketType market) {
        Status current = statuses.get(market);
        if (current.state() != MarketHealthState.MAINTENANCE) {
            return;
        }
        if (properties.enabled()) {
            recover(market, current);
        } else {
            resume(market, current);
        }
        log.info("D-158 {} maintenance ended", market);
        changed(market, MarketHealthState.MAINTENANCE, statuses.get(market).state());
    }

    @Override
    @Transactional
    public synchronized MarketHealthState evaluate(MarketType market, HealthSignals signals, IncidentReason reason) {
        Status current = statuses.get(market);
        MarketHealthState from = current.state();
        MarketHealthState to =
                machine.next(from, signals.withMaintenance(from == MarketHealthState.MAINTENANCE), clock.instant());
        if (to == from) {
            return to;
        }
        if (MarketHealthMachine.opensIncident(from, to)) {
            pause(market, signals, reason);
        } else if (MarketHealthMachine.closesIncident(from, to)) {
            resume(market, current);
        } else if (to == MarketHealthState.RECOVERING) {
            recover(market, current);
        } else if (from == MarketHealthState.RECOVERING && to == MarketHealthState.PAUSED) {
            incidents.relapse(current.incidentId());
            statuses.put(market, new Status(to, current.incidentId(), current.since()));
            log.warn(
                    "D-158 {} lost its data again while recovering; incident {} goes on", market, current.incidentId());
        } else {
            statuses.put(market, new Status(to, current.incidentId(), current.since()));
        }
        changed(market, from, to);
        return to;
    }

    private void pause(MarketType market, HealthSignals signals, IncidentReason reason) {
        UUID id = UuidV7.next();
        Instant now = clock.instant();
        Instant lastLiveAt = signals.lastLiveAt().isAfter(now) ? now : signals.lastLiveAt();
        incidents.open(
                new MarketIncident(id, market, IncidentKind.OUTAGE, reason, lastLiveAt, now, null, null, null, null));
        statuses.put(market, new Status(MarketHealthState.PAUSED, id, lastLiveAt));
        events.publishEvent(new MarketTradingPaused(market, id, MarketHealthState.PAUSED, reason, lastLiveAt));
        log.warn("D-158 {} trading paused ({}): no live data since {}", market, reason, lastLiveAt);
    }

    private void recover(MarketType market, Status current) {
        incidents.recovering(current.incidentId(), clock.instant());
        statuses.put(market, new Status(MarketHealthState.RECOVERING, current.incidentId(), current.since()));
        events.publishEvent(new MarketRecoveryStarted(market, current.incidentId(), current.since()));
        log.info("D-158 {} recovering: replaying from {}", market, current.since());
    }

    private void resume(MarketType market, Status current) {
        Instant now = clock.instant();
        incidents.resume(current.incidentId(), now);
        statuses.put(market, Status.LIVE);
        events.publishEvent(new MarketTradingResumed(market, current.incidentId(), current.since(), now));
        log.info("D-158 {} trading resumed; incident {} closed", market, current.incidentId());
    }

    private void changed(MarketType market, MarketHealthState from, MarketHealthState to) {
        if (from != to) {
            events.publishEvent(new MarketHealthChanged(market, from, to));
        }
    }

    /** A market's state, its open incident if any, and since when it has not been live. */
    private record Status(MarketHealthState state, UUID incidentId, Instant since) {

        private static final Status LIVE = new Status(MarketHealthState.LIVE, null, null);
    }
}
