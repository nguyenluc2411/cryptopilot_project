package com.cryptopilot.trading.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What the matching engine decided from one price range: a waiting plan is invalidated before its entry fills. It is
 * stored by a compare-and-set on the plan's status, so of two racing writers exactly one wins. Entries and exits are
 * the demo account's paper orders since RF-33 (D-117).
 *
 * <p>Rule: NSF-07, BR-68; TECHNICAL_DESIGN 7.7; D-111 (M3); D-117.
 */
public sealed interface PlanOutcome permits Invalidation {

    /** The plan. */
    UUID planId();

    /** When the outcome counts: the open time of the candle that decided it (D-78). */
    Instant at();
}
