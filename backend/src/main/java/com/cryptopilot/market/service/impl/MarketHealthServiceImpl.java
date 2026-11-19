package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The data health of each market (D-158): the current state in memory, each closed period in
 * {@code market_incident}.
 *
 * <ul>
 *   <li>At start, a market with an open incident starts closed (E1): under maintenance if that is what the incident
 *       is, paused otherwise, never live before it has recovered.
 *   <li>Maintenance opens an incident, or turns the open outage into maintenance (E13). Ending it reopens the market
 *       through recovery while the monitor runs; with the monitor off, as in tests, at once, as there is nothing to
 *       recover.
 * </ul>
 *
 * <p>Rule: NSF-03; D-158.
 */
@Service
public class MarketHealthServiceImpl implements MarketHealthService {

    private static final Logger log = LoggerFactory.getLogger(MarketHealthServiceImpl.class);

    private final MarketHealthProperties properties;
    private final MarketIncidentRepository incidents;
    private final Clock clock;
    private final Map<MarketType, Closed> closed = new EnumMap<>(MarketType.class);

    public MarketHealthServiceImpl(MarketHealthProperties properties, MarketIncidentRepository incidents, Clock clock) {
        this.properties = properties;
        this.incidents = incidents;
        this.clock = clock;
        for (MarketIncident open : incidents.findAllOpen()) {
            MarketHealthState state =
                    open.kind() == IncidentKind.MAINTENANCE ? MarketHealthState.MAINTENANCE : MarketHealthState.PAUSED;
            closed.put(open.market(), new Closed(state, open.id(), open.lastLiveAt()));
            log.warn("D-158 {} starts {}: incident {} open since {}", open.market(), state, open.id(), open.pausedAt());
        }
    }

    @Override
    public synchronized MarketHealthState state(MarketType market) {
        Closed current = closed.get(market);
        return current == null ? MarketHealthState.LIVE : current.state();
    }

    @Override
    public void requireTrading(MarketType market) {
        Closed current;
        synchronized (this) {
            current = closed.get(market);
        }
        if (current != null && !current.state().tradingOpen()) {
            throw new TradingPausedException(market, current.state(), current.since());
        }
    }

    @Override
    public synchronized void startMaintenance(MarketType market, String message, UUID by) {
        Instant now = clock.instant();
        Closed current = closed.get(market);
        if (current == null) {
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
            closed.put(market, new Closed(MarketHealthState.MAINTENANCE, id, now));
        } else {
            incidents.toMaintenance(current.incidentId(), message, by);
            closed.put(market, new Closed(MarketHealthState.MAINTENANCE, current.incidentId(), current.since()));
        }
        log.warn("D-158 {} under maintenance", market);
    }

    @Override
    public synchronized void endMaintenance(MarketType market) {
        Closed current = closed.get(market);
        if (current == null || current.state() != MarketHealthState.MAINTENANCE) {
            return;
        }
        if (properties.enabled()) {
            incidents.recovering(current.incidentId(), clock.instant());
            closed.put(market, new Closed(MarketHealthState.RECOVERING, current.incidentId(), current.since()));
        } else {
            incidents.resume(current.incidentId(), clock.instant());
            closed.remove(market);
        }
        log.info("D-158 {} maintenance ended", market);
    }

    /** A market not live: its state, its open incident, and since when it has not been live. */
    private record Closed(MarketHealthState state, UUID incidentId, Instant since) {}
}
