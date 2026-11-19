package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;

/**
 * What the {@code market} module tells other modules about whether a market may be traded now (D-158): while its
 * exchange data is lost, being caught up on, or under maintenance, nothing that changes an order, a position or a
 * wallet of that market is taken, and no price of another source ever stands in for the exchange's.
 *
 * <p>Rule: NSF-03; D-158.
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
}
