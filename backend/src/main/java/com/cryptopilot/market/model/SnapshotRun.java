package com.cryptopilot.market.model;

import java.time.Instant;

/**
 * What one run of the periodic snapshots wrote.
 *
 * <p>Rule: NSF-03.
 *
 * @param at the snapshot time written
 * @param spotRows Spot rows written
 * @param futuresRows futures rows written
 * @param stale values skipped because they were older than the allowed age
 */
public record SnapshotRun(Instant at, int spotRows, int futuresRows, int stale) {}
