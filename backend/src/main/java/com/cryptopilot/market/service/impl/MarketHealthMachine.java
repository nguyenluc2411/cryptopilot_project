package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.enums.MarketHealthState;
import java.time.Instant;

/**
 * The state machine of one market's data health (D-158): from the current state and what the monitor sees now, the
 * next state. Pure; the monitor calls it every tick.
 *
 * <p>Rule: NSF-03; D-158.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 (circuit breaker:
 * closed, open, half-open).
 */
final class MarketHealthMachine {

    private final MarketHealthProperties thresholds;

    MarketHealthMachine(MarketHealthProperties thresholds) {
        this.thresholds = thresholds;
    }

    /** The state after {@code current}, given the signals at {@code now}. */
    MarketHealthState next(MarketHealthState current, HealthSignals signals, Instant now) {
        // K4 (D-158): the transitions are written once the matrix of MarketHealthMachineTest is in place.
        return current;
    }

    /** Whether moving from {@code from} to {@code to} begins an incident (trading closes). */
    static boolean opensIncident(MarketHealthState from, MarketHealthState to) {
        return false;
    }

    /** Whether moving from {@code from} to {@code to} ends the open incident (trading reopens). */
    static boolean closesIncident(MarketHealthState from, MarketHealthState to) {
        return false;
    }
}
