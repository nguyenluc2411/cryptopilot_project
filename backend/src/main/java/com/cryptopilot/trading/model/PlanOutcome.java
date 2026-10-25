package com.cryptopilot.trading.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What the matching engine decided from one price range: a waiting plan's entry fills or it is invalidated first, or
 * an open position exits. Each is stored by a compare-and-set, on the plan's status or on the journal row's, so of two
 * racing writers exactly one wins; the worker retries all of them the same way.
 *
 * <p>Rule: NSF-07, BR-33, BR-35, BR-68; TECHNICAL_DESIGN 7.7; D-111 (M3).
 */
public sealed interface PlanOutcome permits Fill, Invalidation, PositionExit {

    /** The plan, or the plan the position came from. */
    UUID planId();

    /** When the outcome counts: the open time of the candle that decided it (D-78). */
    Instant at();
}
