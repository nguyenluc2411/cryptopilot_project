package com.cryptopilot.market.service;

import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.model.DroppedGap;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.List;

/**
 * The gaps NSF-02 gave up on. A gap whose fill keeps failing for a reason other than the exchange is dropped from
 * the queue so that the market's backfill goes on; it is recorded here so that the drop can be queried rather than
 * only read from a log, and so that the same range is not queued again on its own — by the stream or by the
 * start-up scan — and fail the same way.
 *
 * <p>Rule: NSF-02, NSF-03; A-33.
 */
public interface DroppedGapService {

    /**
     * Records that this gap was dropped at this instant after this many consecutive failures, the last of them this
     * one.
     *
     * <p>Only a one-line summary of the failure is kept (its type and the first line of its message, cut to the
     * column), never a stack trace: the stack trace stays in the error log.
     */
    void recordDropped(GapDetected gap, int failureCount, RuntimeException lastFailure, Instant droppedAt);

    /** Whether a dropped range of the same pair, market and timeframe contains the whole of this gap. */
    boolean isDropped(GapDetected gap);

    /** The dropped gaps of a market, most recently dropped first. */
    List<DroppedGap> droppedGaps(MarketType market);
}
