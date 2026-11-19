package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketHealth;
import com.cryptopilot.market.RecoveryProgress;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The data health of each market inside the {@code market} module (D-158): what {@link MarketHealth} tells the other
 * modules, the maintenance switch of the administration console, and the monitor's tick.
 *
 * <p>Rule: NSF-03; D-158.
 */
public interface MarketHealthService extends MarketHealth {

    /** Closes trading on the market on purpose, with an optional message for the Traders. */
    void startMaintenance(MarketType market, String message, UUID by);

    /** Closes trading on the market on purpose, with an optional message and expected end for the Traders. */
    void startMaintenance(MarketType market, String message, Instant expectedEndAt, UUID by);

    /** Ends maintenance; the market reopens through recovery, never straight away while its data is lost. */
    void endMaintenance(MarketType market);

    /**
     * Reopens a recovering market by an administrator's decision, though its engines have not said they caught up:
     * for an outage longer than can be replayed (D-158). Refused with {@code DATA_CONFLICT} unless the market is
     * recovering, so never while its data is lost.
     */
    void forceResume(MarketType market, UUID by);

    /**
     * One tick of the monitor: moves the market to the state its signals call for, records the incident and publishes
     * what changed. Maintenance is the service's own; the signals' flag is ignored.
     *
     * @param reason what to record if the tick closes trading
     * @return the state after the tick
     */
    MarketHealthState evaluate(MarketType market, HealthSignals signals, IncidentReason reason);

    /** Records how far the modules have caught up with the recovering market, as the monitor last read it. */
    void recoveryProgress(MarketType market, RecoveryProgress progress);

    /** How far the modules have caught up with the market, while it recovers; empty otherwise. */
    Optional<RecoveryProgress> recoveryProgress(MarketType market);

    /** The market's open incident, if trading on it is closed. */
    Optional<MarketIncident> openIncident(MarketType market);
}
