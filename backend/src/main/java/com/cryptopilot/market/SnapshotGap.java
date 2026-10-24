package com.cryptopilot.market;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A stretch without mark price snapshots of a pair: two consecutive snapshots, or a snapshot and an end of the window
 * read, more than one snapshot period apart. A settlement inside it may have been announced by no snapshot at all.
 *
 * <p>Rule: NSF-03, BR-37; A-05.
 *
 * @param from the snapshot before the gap, or the start of the window read
 * @param to the snapshot after the gap, or the end of the window read
 */
public record SnapshotGap(Instant from, Instant to) {

    public SnapshotGap {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("a gap ends after it starts: " + from + " to " + to);
        }
    }

    /** How long the gap lasts. */
    public Duration length() {
        return Duration.between(from, to);
    }
}
