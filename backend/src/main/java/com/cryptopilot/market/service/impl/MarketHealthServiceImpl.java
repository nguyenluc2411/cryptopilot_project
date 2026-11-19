package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The data health of each market (D-158), kept in memory.
 *
 * <p>With the monitor off, as in tests, a market is closed only by maintenance and ending it reopens the market at
 * once, as there is nothing to recover. The monitor, the incident record and the recovery steps come with K2, K4 and
 * K6.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Service
public class MarketHealthServiceImpl implements MarketHealthService {

    private final MarketHealthProperties properties;
    private final Clock clock;
    private final Map<MarketType, Closed> closed = new EnumMap<>(MarketType.class);

    public MarketHealthServiceImpl(MarketHealthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
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
        Closed current = closed.get(market);
        Instant since = current == null ? clock.instant() : current.since();
        closed.put(market, new Closed(MarketHealthState.MAINTENANCE, since, message));
    }

    @Override
    public synchronized void endMaintenance(MarketType market) {
        Closed current = closed.get(market);
        if (current == null || current.state() != MarketHealthState.MAINTENANCE) {
            return;
        }
        if (properties.enabled()) {
            closed.put(market, new Closed(MarketHealthState.RECOVERING, current.since(), null));
        } else {
            closed.remove(market);
        }
    }

    /** A market not live: its state, since when, and the maintenance message if any. */
    private record Closed(MarketHealthState state, Instant since, String message) {}
}
