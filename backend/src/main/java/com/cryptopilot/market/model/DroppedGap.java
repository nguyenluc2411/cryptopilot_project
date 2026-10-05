package com.cryptopilot.market.model;

import com.cryptopilot.market.event.GapDetected;
import java.time.Instant;

/**
 * A queued gap the backfill gave up on after failing to fill it, for a reason other than the exchange, too many
 * times in a row. Recorded so that the drop can be found later and the same range is not queued again on its own.
 *
 * <p>Rule: NSF-02, NSF-03.
 *
 * @param gap the range that was given up, as it was queued
 * @param failureCount the consecutive failures that led to the drop
 * @param lastError a one-line summary of the last failure: its type and the first line of its message
 * @param droppedAt when the gap was dropped
 */
public record DroppedGap(GapDetected gap, int failureCount, String lastError, Instant droppedAt) {}
