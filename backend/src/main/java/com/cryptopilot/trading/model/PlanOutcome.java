package com.cryptopilot.trading.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What the matching engine decided for a waiting plan from one price range: its entry fills, or it is invalidated
 * first. Either is stored by a compare-and-set on the plan's status, so of two racing writers exactly one wins.
 *
 * <p>Rule: NSF-07, BR-33, BR-68; TECHNICAL_DESIGN 7.7; D-111 (M3).
 */
public sealed interface PlanOutcome permits Fill, Invalidation {

    /** The plan as the engine tracked it. */
    TrackedEntry entry();

    /** When the outcome counts: the open time of the candle that decided it (D-78). */
    Instant at();

    /** The plan. */
    default UUID planId() {
        return entry().planId();
    }
}
