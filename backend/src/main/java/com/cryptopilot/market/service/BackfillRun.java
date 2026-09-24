package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What one backfill run of one market did.
 *
 * <p>Rule: NSF-02.
 *
 * @param market the market backfilled
 * @param seriesCompleted how many (pair, timeframe) series were brought up to date
 * @param rowsInserted how many candles were new
 * @param pausedUntil when the run stopped to leave weight for other jobs, the instant it may continue; empty
 *     when it finished
 * @param defects the series skipped this run because the exchange rejected the request or sent an unreadable
 *     answer
 */
public record BackfillRun(
        MarketType market, int seriesCompleted, int rowsInserted, Optional<Instant> pausedUntil, List<String> defects) {

    /** Whether the run stopped early to respect the weight budget. */
    public boolean paused() {
        return pausedUntil.isPresent();
    }
}
