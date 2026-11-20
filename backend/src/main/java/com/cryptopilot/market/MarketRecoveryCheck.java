package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;

/**
 * What another module answers when a market recovers from an incident (D-159): whether everything it runs on that
 * market's prices has caught up with the exchange, so trading may reopen. The paper engine answers for its waiting
 * orders, positions and timed work, the plan engine for its plans; the market reopens only once every answer is yes.
 *
 * <p>Rule: NSF-03; D-159.
 */
public interface MarketRecoveryCheck {

    /**
     * How many of the pairs this module runs on the market's prices have replayed the data missed since
     * {@code lastLiveAt}, up to now, with the work that fell due meanwhile run.
     */
    RecoveryProgress progress(MarketType market, Instant lastLiveAt);

    /** Whether everything this module runs on the market's prices has caught up. */
    default boolean caughtUp(MarketType market, Instant lastLiveAt) {
        return progress(market, lastLiveAt).done();
    }
}
