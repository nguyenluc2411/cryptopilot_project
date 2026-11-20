package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.enums.MarketHealthState;
import java.time.Duration;
import java.time.Instant;

/**
 * The state machine of one market's data health (D-159): from the current state and what the monitor sees now, the
 * next state. Pure; the monitor calls it every tick.
 *
 * <p>Rule: NSF-03; D-159.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 (circuit breaker:
 * closed, open, half-open).
 */
final class MarketHealthMachine {

    private final MarketHealthProperties thresholds;

    MarketHealthMachine(MarketHealthProperties thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * The state after {@code current}, given the signals at {@code now}:
     *
     * <ul>
     *   <li>maintenance holds every state as {@code MAINTENANCE};
     *   <li>live or reconnecting: reconnecting after {@code reconnectingAfter} without data, paused after
     *       {@code pauseAfter}, or sooner when the REST API is down as well;
     *   <li>paused, or maintenance ended: recovering once the data has flowed for {@code stableBeforeRecover} without a
     *       break and the REST API answers, which the replay needs; never live before recovering;
     *   <li>recovering: live once recovery is done; paused again on any break in the data.
     * </ul>
     */
    MarketHealthState next(MarketHealthState current, HealthSignals signals, Instant now) {
        if (signals.maintenance()) {
            return MarketHealthState.MAINTENANCE;
        }
        Duration silence = Duration.between(signals.lastLiveAt(), now);
        boolean flowing = silence.compareTo(thresholds.reconnectingAfter()) < 0;
        return switch (current) {
            case LIVE, RECONNECTING -> {
                if (silence.compareTo(thresholds.pauseAfter()) >= 0) {
                    yield MarketHealthState.PAUSED;
                }
                if (flowing) {
                    yield MarketHealthState.LIVE;
                }
                yield signals.restAvailable() ? MarketHealthState.RECONNECTING : MarketHealthState.PAUSED;
            }
            case PAUSED, MAINTENANCE ->
                stable(signals, now, flowing) && signals.restAvailable()
                        ? MarketHealthState.RECOVERING
                        : MarketHealthState.PAUSED;
            case RECOVERING -> {
                if (!flowing) {
                    yield MarketHealthState.PAUSED;
                }
                yield signals.recoveryDone() ? MarketHealthState.LIVE : MarketHealthState.RECOVERING;
            }
        };
    }

    private boolean stable(HealthSignals signals, Instant now, boolean flowing) {
        return flowing && Duration.between(signals.liveSince(), now).compareTo(thresholds.stableBeforeRecover()) >= 0;
    }

    /** Whether moving from {@code from} to {@code to} begins an incident (trading closes). */
    static boolean opensIncident(MarketHealthState from, MarketHealthState to) {
        return from.tradingOpen() && !to.tradingOpen();
    }

    /** Whether moving from {@code from} to {@code to} ends the open incident (trading reopens). */
    static boolean closesIncident(MarketHealthState from, MarketHealthState to) {
        return !from.tradingOpen() && to.tradingOpen();
    }
}
