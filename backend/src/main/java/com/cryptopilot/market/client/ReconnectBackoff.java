package com.cryptopilot.market.client;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * How long a stream connection waits before its next attempt: 1 s, 2 s, 4 s … doubling up to 60 s, each
 * shortened by a random share of at most {@code jitterPercent}, so that the connections that dropped together
 * do not all come back in the same millisecond.
 *
 * <p>The jitter only ever shortens the wait, never lengthens it, so the ceiling of NSF-03 — 60 s — holds for
 * every attempt. Spot allows 300 connection attempts per 5 minutes per IP (TECHNICAL_DESIGN 7.1.1); a handful
 * of shards backing off from one second stays far below it.
 *
 * <p>Rule: NSF-03 (exponential back-off from 1 s up to 60 s); TECHNICAL_DESIGN 7.1 step 6.
 *
 * <p>Reference: Brooker, M. (2015). "Exponential Backoff And Jitter". <i>AWS Architecture Blog</i>
 * (randomised back-off spreads the reconnections of clients that failed together).
 *
 * @param initial the wait before the first retry
 * @param max the longest wait
 * @param jitterPercent the largest share, in percent, taken off a wait at random
 * @param random a source of numbers in {@code [0, 1)}
 */
public record ReconnectBackoff(Duration initial, Duration max, int jitterPercent, DoubleSupplier random) {

    /** The wait before retry number {@code attempt}, counted from zero after the last successful connection. */
    public Duration delay(int attempt) {
        long ceiling = max.toMillis();
        long grown = initial.toMillis();
        for (int i = 0; i < attempt && grown < ceiling; i++) {
            grown *= 2;
        }
        long capped = Math.min(grown, ceiling);
        long jitter = (long) (capped * (jitterPercent / 100.0) * random.getAsDouble());
        return Duration.ofMillis(capped - jitter);
    }
}
