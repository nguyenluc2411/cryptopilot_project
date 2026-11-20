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
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The data health of each market (D-159): the current state in memory, each closed period in
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
 *       monitor off or not running (no streams), as in tests, at once, as nothing would end the recovery.
 * </ul>
 *
 * <p>The changes are written and published in one transaction, so the listeners of other modules hear them after
 * the incident is stored.
 *
 * <p>The state in memory follows the database: a change made inside a transaction is staged, seen by that
 * transaction alone, and applied only after it commits, so a rollback (a failed audit, say) leaves no phantom state.
 * Two transitions computed from the same state cannot both land: each status holds the version of its incident row,
 * every update names that version and bumps it, so the second changes no row, throws and rolls back (a second open
 * meets the one-open-incident index). A committed change whose start state moved meanwhile, one that wrote no row,
 * reloads the market from the database instead. References: Fowler, M. (2002). Patterns of Enterprise Application
 * Architecture. Addison-Wesley, "Unit of Work" and "Optimistic Offline Lock"; Spring Framework reference,
 * "Transaction-bound events" and {@code TransactionSynchronization}.
 *
 * <p>Rule: NSF-03; D-159.
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
    private volatile boolean monitorRunning;

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
            Status restored = restored(open);
            statuses.put(open.market(), restored);
            log.warn(
                    "D-159 {} starts {}: incident {} open since {}",
                    open.market(),
                    restored.state(),
                    open.id(),
                    open.pausedAt());
        }
        for (MarketType market : MarketType.values()) {
            if (statuses.get(market).incidentId() == null) {
                restoreEnded(market);
            }
        }
    }

    /** The status of a market whose incident is open in the database, as at start. */
    private Status restored(MarketIncident open) {
        boolean maintenance = open.kind() == IncidentKind.MAINTENANCE;
        MarketHealthState state = maintenance ? MarketHealthState.MAINTENANCE : MarketHealthState.PAUSED;
        // Maintenance was announced when it began; an outage if it was open long enough before the restart.
        boolean announced =
                maintenance || !open.pausedAt().plus(properties.notifyAfter()).isAfter(clock.instant());
        // An outage lost data; so did a maintenance that took one over, as its data stopped before it closed.
        boolean lost = open.kind() == IncidentKind.OUTAGE || open.lastLiveAt().isBefore(open.pausedAt());
        return Status.closed(state, open.id(), open.lastLiveAt(), open.pausedAt(), announced, lost)
                .at(open.version());
    }

    private void restoreEnded(MarketType market) {
        incidents.findRecent(market, 1).stream()
                .filter(incident -> incident.resumedAt() != null)
                .findFirst()
                .ifPresent(incident -> ended.put(market, new Ended(incident.lastLiveAt(), incident.resumedAt())));
    }

    @Override
    public synchronized MarketHealthState state(MarketType market) {
        return current(market).state();
    }

    @Override
    public void monitorStarted() {
        monitorRunning = true;
    }

    @Override
    public synchronized boolean dataLost(MarketType market) {
        Status current = current(market);
        return current.state() == MarketHealthState.PAUSED
                || (current.state() == MarketHealthState.RECOVERING && current.lost());
    }

    @Override
    public void requireTrading(MarketType market) {
        Status current;
        synchronized (this) {
            current = current(market);
        }
        if (!current.state().tradingOpen()) {
            throw new TradingPausedException(market, current.state(), current.since());
        }
    }

    @Override
    public synchronized Optional<Instant> catchUpFrom(MarketType market) {
        Instant now = clock.instant();
        Status current = current(market);
        Instant from = null;
        if (current.incidentId() != null) {
            from = current.since();
        } else {
            Ended last = endedOf(market);
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
        Status current = current(market);
        if (current.state() != MarketHealthState.RECOVERING) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    market + " is " + current.state() + ", not recovering: only a recovering market can be reopened");
        }
        resume(market, current);
        log.warn("D-159 {} reopened by administrator {} before its engines caught up", market, by);
        changed(market, MarketHealthState.RECOVERING, MarketHealthState.LIVE);
    }

    @Override
    public synchronized void recoveryProgress(MarketType market, RecoveryProgress read) {
        if (current(market).state() == MarketHealthState.RECOVERING) {
            progress.put(market, read);
        }
    }

    @Override
    public synchronized Optional<RecoveryProgress> recoveryProgress(MarketType market) {
        if (current(market).state() != MarketHealthState.RECOVERING) {
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
        Status current = current(market);
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
            incidents.toMaintenance(current.incidentId(), current.version(), message, expectedEndAt, by);
            held = current.in(MarketHealthState.MAINTENANCE).written();
        }
        // Maintenance is decided on purpose: the Traders are told at once, not after notify-after.
        stage(market, announce(market, held, IncidentReason.MAINTENANCE, message));
        log.warn("D-159 {} under maintenance", market);
        changed(market, current.state(), MarketHealthState.MAINTENANCE);
    }

    @Override
    @Transactional
    public synchronized void endMaintenance(MarketType market) {
        Status current = current(market);
        if (current.state() != MarketHealthState.MAINTENANCE) {
            return;
        }
        if (properties.enabled() && monitorRunning) {
            recover(market, current);
        } else {
            resume(market, current);
        }
        log.info("D-159 {} maintenance ended", market);
        changed(market, MarketHealthState.MAINTENANCE, current(market).state());
    }

    @Override
    @Transactional
    public synchronized MarketHealthState evaluate(MarketType market, HealthSignals signals, IncidentReason reason) {
        Status current = current(market);
        MarketHealthState from = current.state();
        if (current.incidentId() != null
                && !current.announced()
                && !current.pausedAt().plus(properties.notifyAfter()).isAfter(clock.instant())) {
            current = current.state() == MarketHealthState.MAINTENANCE
                    ? announce(market, current, IncidentReason.MAINTENANCE, maintenanceMessage(market))
                    : announce(market, current, reason, null);
            stage(market, current);
        }
        boolean beyondReplay = beyondReplay(current);
        if (beyondReplay) {
            // Not all of the missed data can be replayed: only an administrator reopens the market (D-159).
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
            incidents.relapse(current.incidentId(), current.version());
            stage(market, current.in(to).dataLostNow().written());
            log.warn(
                    "D-159 {} lost its data again while recovering; incident {} goes on", market, current.incidentId());
        } else {
            Status next = current.in(to);
            stage(market, to == MarketHealthState.PAUSED ? next.dataLostNow() : next);
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
        stage(market, Status.closed(MarketHealthState.PAUSED, id, lastLiveAt, now, false, true));
        events.publishEvent(new MarketTradingPaused(market, id, MarketHealthState.PAUSED, reason, lastLiveAt));
        log.warn("D-159 {} trading paused ({}): no live data since {}", market, reason, lastLiveAt);
    }

    private String maintenanceMessage(MarketType market) {
        return incidents.findOpen(market).map(MarketIncident::message).orElse(null);
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
        incidents.recovering(current.incidentId(), current.version(), now);
        Status recovering = current.recoveringFrom(now).written();
        stage(market, recovering);
        events.publishEvent(new MarketRecoveryStarted(market, current.incidentId(), current.since()));
        log.info("D-159 {} recovering: replaying from {}", market, current.since());
        if (beyondReplay(recovering)) {
            tellOverdue(market, recovering, true);
        }
    }

    /** Whether the incident's data was lost longer than the engines replay (D-159). */
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
        stage(market, current.overdueToldNow());
        log.warn(
                "D-159 {} recovery of incident {} needs an administrator: {}",
                market,
                current.incidentId(),
                beyondReplay ? "data lost longer than max-outage-replay" : "running since " + current.recoveringAt());
    }

    private void resume(MarketType market, Status current) {
        Instant now = clock.instant();
        incidents.resume(current.incidentId(), current.version(), now);
        stage(market, Status.LIVE);
        stageEnded(market, new Ended(current.since(), now));
        events.publishEvent(
                new MarketTradingResumed(market, current.incidentId(), current.since(), now, current.announced()));
        log.info("D-159 {} trading resumed; incident {} closed", market, current.incidentId());
    }

    private void changed(MarketType market, MarketHealthState from, MarketHealthState to) {
        if (to != MarketHealthState.RECOVERING) {
            stageProgressCleared(market);
        }
        if (from != to) {
            events.publishEvent(new MarketHealthChanged(market, from, to));
        }
    }

    /** The market's state as this transaction sees it: its own staged change, else the committed one. */
    private Status current(MarketType market) {
        Staged staged = stagedOf(market);
        return staged == null ? statuses.get(market) : staged.status;
    }

    private Ended endedOf(MarketType market) {
        Staged staged = stagedOf(market);
        return staged == null || staged.ended == null ? ended.get(market) : staged.ended;
    }

    private void stage(MarketType market, Status next) {
        Map<MarketType, Staged> staging = staging();
        if (staging == null) {
            statuses.put(market, next);
        } else {
            staging.computeIfAbsent(market, this::stagedFrom).status = next;
        }
    }

    private void stageEnded(MarketType market, Ended next) {
        Map<MarketType, Staged> staging = staging();
        if (staging == null) {
            ended.put(market, next);
        } else {
            staging.computeIfAbsent(market, this::stagedFrom).ended = next;
        }
    }

    private void stageProgressCleared(MarketType market) {
        Map<MarketType, Staged> staging = staging();
        if (staging == null) {
            progress.remove(market);
        } else {
            staging.computeIfAbsent(market, this::stagedFrom).progressCleared = true;
        }
    }

    private Staged stagedFrom(MarketType market) {
        return new Staged(statuses.get(market));
    }

    private Staged stagedOf(MarketType market) {
        Map<MarketType, Staged> bound = bound();
        return bound == null ? null : bound.get(market);
    }

    @SuppressWarnings("unchecked")
    private Map<MarketType, Staged> bound() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                ? (Map<MarketType, Staged>) TransactionSynchronizationManager.getResource(this)
                : null;
    }

    /** This transaction's staged changes, bound on first use; {@code null} outside a transaction (applied at once). */
    private Map<MarketType, Staged> staging() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Map<MarketType, Staged> staging = bound();
        if (staging == null) {
            staging = new EnumMap<>(MarketType.class);
            TransactionSynchronizationManager.bindResource(this, staging);
            TransactionSynchronizationManager.registerSynchronization(new ApplyAfterCommit(staging));
        }
        return staging;
    }

    /**
     * Applies a committed transaction's changes, each if the state it started from is still the one held; otherwise
     * reloads that market from the database, which holds what both transactions committed.
     */
    private synchronized void apply(Map<MarketType, Staged> staging) {
        staging.forEach((market, staged) -> {
            if (statuses.get(market) != staged.from) {
                resync(market);
                return;
            }
            statuses.put(market, staged.status);
            if (staged.ended != null) {
                ended.put(market, staged.ended);
            }
            if (staged.progressCleared) {
                progress.remove(market);
            }
        });
    }

    private void resync(MarketType market) {
        Optional<MarketIncident> open = incidents.findOpen(market);
        statuses.put(market, open.map(this::restored).orElse(Status.LIVE));
        if (open.isEmpty()) {
            restoreEnded(market);
        }
        progress.remove(market);
        log.warn(
                "D-159 {} state moved under a committed transaction: reloaded as {}",
                market,
                statuses.get(market).state());
    }

    /** One market's change staged by a transaction: the state it started from and what it becomes. */
    private static final class Staged {

        private final Status from;
        private Status status;
        private Ended ended;
        private boolean progressCleared;

        private Staged(Status from) {
            this.from = from;
            this.status = from;
        }
    }

    /** Applies the staged changes after commit, first of the synchronizations, so the status pushes read them. */
    private final class ApplyAfterCommit implements TransactionSynchronization {

        private final Map<MarketType, Staged> staging;

        private ApplyAfterCommit(Map<MarketType, Staged> staging) {
            this.staging = staging;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(MarketHealthServiceImpl.this);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(MarketHealthServiceImpl.this, staging);
        }

        @Override
        public void afterCommit() {
            apply(staging);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(MarketHealthServiceImpl.this);
        }
    }

    /**
     * One market's state in memory.
     *
     * @param lost whether the exchange data was really lost during the open incident: set by an outage or a relapse,
     *     kept when maintenance takes the incident over, so the recovery after it shows the fallback only then
     * @param version the version of the incident row this status was read or written as
     */
    private record Status(
            MarketHealthState state,
            UUID incidentId,
            Instant since,
            Instant pausedAt,
            boolean announced,
            Instant recoveringAt,
            boolean overdueTold,
            boolean lost,
            int version) {

        private static final Status LIVE =
                new Status(MarketHealthState.LIVE, null, null, null, false, null, false, false, 0);

        private static Status closed(
                MarketHealthState state,
                UUID incidentId,
                Instant since,
                Instant pausedAt,
                boolean announced,
                boolean lost) {
            return new Status(state, incidentId, since, pausedAt, announced, null, false, lost, 0);
        }

        private Status at(int rowVersion) {
            return new Status(
                    state, incidentId, since, pausedAt, announced, recoveringAt, overdueTold, lost, rowVersion);
        }

        /** After an update of the incident row, which bumped its version. */
        private Status written() {
            return at(version + 1);
        }

        private Status in(MarketHealthState next) {
            return new Status(next, incidentId, since, pausedAt, announced, recoveringAt, overdueTold, lost, version);
        }

        private Status announcedNow() {
            return new Status(state, incidentId, since, pausedAt, true, recoveringAt, overdueTold, lost, version);
        }

        private Status recoveringFrom(Instant at) {
            return new Status(
                    MarketHealthState.RECOVERING,
                    incidentId,
                    since,
                    pausedAt,
                    announced,
                    at,
                    overdueTold,
                    lost,
                    version);
        }

        private Status overdueToldNow() {
            return new Status(state, incidentId, since, pausedAt, announced, recoveringAt, true, lost, version);
        }

        private Status dataLostNow() {
            return new Status(state, incidentId, since, pausedAt, announced, recoveringAt, overdueTold, true, version);
        }
    }

    /** When the data of a market's last incident was last live, and when trading reopened. */
    private record Ended(Instant lastLiveAt, Instant resumedAt) {}
}
