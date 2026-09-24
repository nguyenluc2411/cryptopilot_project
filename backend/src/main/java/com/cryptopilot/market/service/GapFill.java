package com.cryptopilot.market.service;

import com.cryptopilot.market.event.GapDetected;
import java.time.Instant;
import java.util.Optional;

/**
 * What filling one gap did.
 *
 * <p>Rule: NSF-02, NSF-03.
 *
 * @param rowsInserted how many candles were new
 * @param remaining what is left of the gap when the weight share stopped the fill; empty when it is filled or
 *     was dropped as a defect
 * @param pausedUntil when the fill may continue, present exactly when {@code remaining} is
 */
public record GapFill(int rowsInserted, Optional<GapDetected> remaining, Optional<Instant> pausedUntil) {}
