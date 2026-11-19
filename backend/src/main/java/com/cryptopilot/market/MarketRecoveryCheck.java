package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;

/**
 * What another module answers when a market recovers from an incident (D-158): whether everything it runs on that
 * market's prices has caught up with the exchange, so trading may reopen. The paper engine answers for its waiting
 * orders, positions and timed work, the plan engine for its plans; the market reopens only once every answer is yes.
 *
 * <p>Rule: NSF-03; D-158.
 */
public interface MarketRecoveryCheck {

    /**
     * Whether everything this module runs on the market's prices has replayed the data missed since
     * {@code lastLiveAt}, up to now, and the work that fell due meanwhile has run.
     */
    boolean caughtUp(MarketType market, Instant lastLiveAt);
}
