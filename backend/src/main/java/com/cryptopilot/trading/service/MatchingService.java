package com.cryptopilot.trading.service;

import com.cryptopilot.trading.model.TrackedEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The storage side of the matching engine: what the books start from and the fills they decide.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 5.5 and 7.7.
 */
public interface MatchingService {

    /** The entries of every ACTIVE LIMIT plan. */
    List<TrackedEntry> activeEntries();

    /**
     * Fills a plan's entry if it is still ACTIVE.
     *
     * @param planId the plan
     * @param at when the price reached the entry
     * @return whether this call filled it; {@code false} when the plan was cancelled, expired or filled first
     */
    boolean fill(UUID planId, Instant at);
}
