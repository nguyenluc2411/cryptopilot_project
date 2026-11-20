package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.RecoveryProgress;
import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
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
import java.time.Duration;
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

    /** How long after an incident ended the engines still catch up from it: the timed work runs once it is open. */
    static final Duration CATCH_UP_AFTER_RESUME = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(MarketHealthServiceImpl.class);

    private final MarketHealthProperties properties;
    private final MarketHealthMachine machine;
    private final MarketIncidentRepository incidents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<MarketType, Status> statuses = new EnumMap<>(MarketType.class);
    private final Map<MarketType, Ended> ended = new EnumMap<>(MarketType.class);
    private final Map<MarketType, RecoveryProgress> progress = new EnumMap<>(MarketType.class);

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
            // Announced before the restart if it was open long enough; a second notice would be written once only.
            boolean announced = !open.pausedAt().plus(properties.notifyAfter()).isAfter(clock.instant());
            // An outage lost data; so did a maintenance that took one over, as its data stopped before it closed.
            boolean lost =
                    open.kind() == IncidentKind.OUTAGE || open.lastLiveAt().isBefore(open.pausedAt());
            statuses.put(
                    open.market(),
                    Status.closed(state, open.id(), open.lastLiveAt(), open.pausedAt(), announced, lost));
            log.warn("D-158 {} starts {}: incident {} open since {}", open.market(), state, open.id(), open.pausedAt());
        }
        for (MarketType market : MarketType.values()) {
            if (statuses.get(market).incidentId() == null) {
                incidents.findRecent(market, 1).stream()
                        .filter(incident -> incident.resumedAt() != null)
                        .findFirst()
                        .ifPresent(
                                incident -> ended.put(market, new Ended(incident.lastLiveAt(), incident.resumedAt())));
            }
        }
    }

    @Override
    public synchronized MarketHealthState state(MarketType market) {
        return statuses.get(market).state();
    }

    @Override
    public synchronized boolean dataLost(MarketType market) {
        Status current = statuses.get(market);
        return current.state() == MarketHealthState.PAUSED
                || (current.state() == MarketHealthState.RECOVERING && current.lost());
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
    public synchronized Optional<Instant> catchUpFrom(MarketType market) {
        Instant now = clock.instant();
        Status current = statuses.get(market);
        Instant from = null;
        if (current.incidentId() != null) {
            from = current.since();
        } else {
            Ended last = ended.get(market);
            if (last != null && last.resumedAt().plus(CATCH_UP_AFTER_RESUME).isAfter(now)) {
                from = last.lastLiveAt();
            }
        }
        if (from == null) {
            return Optional.empty();
        }
        Instant oldest = now.minus(properties.maxOutageReplay());
        return Optional.of(from.isBefore(oldest) ? oldest : from);
    }

    @Override
    @Transactional
    public synchronized void forceResume(MarketType market, UUID by) {
        Status current = statuses.get(market);
        if (current.state() != MarketHealthState.RECOVERING) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    market + " is " + current.state() + ", not recovering: only a recovering market can be reopened");
        }
        resume(market, current);
        log.warn("D-158 {} reopened by administrator {} before its engines caught up", market, by);
        changed(market, MarketHealthState.RECOVERING, MarketHealthState.LIVE);
    }

    @Override
    public synchronized void recoveryProgress(MarketType market, RecoveryProgress read) {
        if (statuses.get(market).state() == MarketHealthState.RECOVERING) {
            progress.put(market, read);
        }
    }

    @Override
    public synchronized Optional<RecoveryProgress> recoveryProgress(MarketType market) {
        if (statuses.get(market).state() != MarketHealthState.RECOVERING) {
            return Optional.empty();
        }
        return Optional.ofNullable(progress.get(market));
    }

    @Override
    public Optional<MarketIncident> openIncident(MarketType market) {
        return incidents.findOpen(market);
    }

    @Override
    @Transactional
    public void startMaintenance(MarketType market, String message, UUID by) {
        startMaintenance(market, message, null, by);
    }

    @Override
    @Transactional
    public synchronized void startMaintenance(MarketType market, String message, Instant expectedEndAt, UUID by) {
        Instant now = clock.instant();
        Status current = statuses.get(market);
        Status held;
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
                    by,
                    expectedEndAt));
            held = Status.closed(MarketHealthState.MAINTENANCE, id, now, now, false, false);
            events.publishEvent(new MarketTradingPaused(
                    market, id, MarketHealthState.MAINTENANCE, IncidentReason.MAINTENANCE, now));
        } else {
            incidents.toMaintenance(current.incidentId(), message, expectedEndAt, by);
            held = current.in(MarketHealthState.MAINTENANCE);
        }
        // Maintenance is decided on purpose: the Traders are told at once, not after notify-after.
        statuses.put(market, announce(market, held, IncidentReason.MAINTENANCE, message));
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
        if (current.incidentId() != null
                && !current.announced()
                && !current.pausedAt().plus(properties.notifyAfter()).isAfter(clock.instant())) {
            current = announce(market, current, reason, null);
            statuses.put(market, current);
        }
        boolean beyondReplay = beyondReplay(current);
        if (beyondReplay) {
            // Not all of the missed data can be replayed: only an administrator reopens the market (D-158).
            signals = signals.withRecoveryDone(false);
        }
        MarketHealthState to =
                machine.next(from, signals.withMaintenance(from == MarketHealthState.MAINTENANCE), clock.instant());
        if (to == MarketHealthState.RECOVERING && from == MarketHealthState.RECOVERING) {
            tellOverdue(market, current, beyondReplay);
        }
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
            statuses.put(market, current.in(to).dataLostNow());
            log.warn(
                    "D-158 {} lost its data again while recovering; incident {} goes on", market, current.incidentId());
        } else {
            Status next = current.in(to);
            statuses.put(market, to == MarketHealthState.PAUSED ? next.dataLostNow() : next);
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
        statuses.put(market, Status.closed(MarketHealthState.PAUSED, id, lastLiveAt, now, false, true));
        events.publishEvent(new MarketTradingPaused(market, id, MarketHealthState.PAUSED, reason, lastLiveAt));
        log.warn("D-158 {} trading paused ({}): no live data since {}", market, reason, lastLiveAt);
    }

    /** Publishes, once per incident, that the Traders should hear of it; returns the status marked announced. */
    private Status announce(MarketType market, Status current, IncidentReason reason, String message) {
        if (current.announced()) {
            return current;
        }
        events.publishEvent(new MarketPauseAnnounced(
                market, current.incidentId(), current.state(), reason, current.since(), message));
        return current.announcedNow();
    }

    private void recover(MarketType market, Status current) {
        Instant now = clock.instant();
        incidents.recovering(current.incidentId(), now);
        Status recovering = current.recoveringFrom(now);
        statuses.put(market, recovering);
        events.publishEvent(new MarketRecoveryStarted(market, current.incidentId(), current.since()));
        log.info("D-158 {} recovering: replaying from {}", market, current.since());
        if (beyondReplay(recovering)) {
            tellOverdue(market, recovering, true);
        }
    }

    /** Whether the incident's data was lost longer than the engines replay (D-158). */
    private boolean beyondReplay(Status current) {
        return current.incidentId() != null
                && current.since().isBefore(clock.instant().minus(properties.maxOutageReplay()));
    }

    /**
     * Tells the administrators, once per incident, of a recovery that needs them: beyond the replay at once, otherwise
     * once it has run longer than recovery-timeout.
     */
    private void tellOverdue(MarketType market, Status current, boolean beyondReplay) {
        if (current.overdueTold() || current.recoveringAt() == null) {
            return;
        }
        if (!beyondReplay
                && current.recoveringAt().plus(properties.recoveryTimeout()).isAfter(clock.instant())) {
            return;
        }
        events.publishEvent(new MarketRecoveryOverdue(
                market, current.incidentId(), current.since(), current.recoveringAt(), beyondReplay));
        statuses.put(market, current.overdueToldNow());
        log.warn(
                "D-158 {} recovery of incident {} needs an administrator: {}",
                market,
                current.incidentId(),
                beyondReplay ? "data lost longer than max-outage-replay" : "running since " + current.recoveringAt());
    }

    private void resume(MarketType market, Status current) {
        Instant now = clock.instant();
        incidents.resume(current.incidentId(), now);
        statuses.put(market, Status.LIVE);
        ended.put(market, new Ended(current.since(), now));
        events.publishEvent(
                new MarketTradingResumed(market, current.incidentId(), current.since(), now, current.announced()));
        log.info("D-158 {} trading resumed; incident {} closed", market, current.incidentId());
    }

    private void changed(MarketType market, MarketHealthState from, MarketHealthState to) {
        if (to != MarketHealthState.RECOVERING) {
            progress.remove(market);
        }
        if (from != to) {
            events.publishEvent(new MarketHealthChanged(market, from, to));
        }
    }

    /**
     * A market's state, its open incident if any, since when it has not been live, when trading closed, whether the
     * Traders were told of the incident, when its current recovery began, and whether the administrators were told it
     * needs them.
     */
    /**
     * One market's state in memory.
     *
     * @param lost whether the exchange data was really lost during the open incident: set by an outage or a relapse,
     *     kept when maintenance takes the incident over, so the recovery after it shows the fallback only then
     */
    private record Status(
            MarketHealthState state,
            UUID incidentId,
            Instant since,
            Instant pausedAt,
            boolean announced,
            Instant recoveringAt,
            boolean overdueTold,
            boolean lost) {

        private static final Status LIVE =
                new Status(MarketHealthState.LIVE, null, null, null, false, null, false, false);

        private static Status closed(
                MarketHealthState state,
                UUID incidentId,
                Instant since,
                Instant pausedAt,
                boolean announced,
                boolean lost) {
            return new Status(state, incidentId, since, pausedAt, announced, null, false, lost);
        }

        private Status in(MarketHealthState next) {
            return new Status(next, incidentId, since, pausedAt, announced, recoveringAt, overdueTold, lost);
        }

        private Status announcedNow() {
            return new Status(state, incidentId, since, pausedAt, true, recoveringAt, overdueTold, lost);
        }

        private Status recoveringFrom(Instant at) {
            return new Status(
                    MarketHealthState.RECOVERING, incidentId, since, pausedAt, announced, at, overdueTold, lost);
        }

        private Status overdueToldNow() {
            return new Status(state, incidentId, since, pausedAt, announced, recoveringAt, true, lost);
        }

        private Status dataLostNow() {
            return new Status(state, incidentId, since, pausedAt, announced, recoveringAt, overdueTold, true);
        }
    }

    /** When the data of a market's last incident was last live, and when trading reopened. */
    private record Ended(Instant lastLiveAt, Instant resumedAt) {}
}
