package com.cryptopilot.trading.service;

import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.TrackedEntry;
import java.util.List;

/**
 * The storage side of the matching engine: what the books start from and the fills they decide.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 5.5 and 7.7.
 */
public interface MatchingService {

    /** The entries of every ACTIVE LIMIT plan. */
    List<TrackedEntry> activeEntries();

    /**
     * Fills a plan's entry if it is still ACTIVE: the plan becomes EXECUTED at the fill's time and price. The journal record of
     * BR-34 is T-048.
     *
     * @param fill the plan, the fill price and the time
     * @return whether this call filled it; {@code false} when the plan was cancelled, expired or filled first
     */
    boolean fill(Fill fill);
}
