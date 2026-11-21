package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Optional;

/**
 * What the {@code market} module tells other modules about whether a market may be traded now (D-159): while its
 * exchange data is lost, being caught up on, or under maintenance, nothing that changes an order, a position or a
 * wallet of that market is taken, and no price of another source ever stands in for the exchange's.
 *
 * <p>Rule: NSF-03; D-159.
 */
public interface MarketHealth {

    /** The current state of the market. */
    MarketHealthState state(MarketType market);

    /** Whether the market may be traded now. */
    default boolean tradingOpen(MarketType market) {
        return state(market).tradingOpen();
    }

    /**
     * Refuses with {@link TradingPausedException} ({@code TRADING_PAUSED}) unless the market may be traded now. Called
     * first by everything that changes an order, a position or a wallet of the market, inside the Trader's lock, so a
     * request racing a pause either completes before it or is refused.
     */
    void requireTrading(MarketType market);

    /**
     * Where the engines replay the market from (D-159): while an incident is open, and for an hour after it
     * ended, when its data was last live, never more than {@code max-outage-replay} back. Empty otherwise: the engines
     * keep their own windows. A replay window or a lookback reaches at least this far back.
     */
    Optional<Instant> catchUpFrom(MarketType market);
}
