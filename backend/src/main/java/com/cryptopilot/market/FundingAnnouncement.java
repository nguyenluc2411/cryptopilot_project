package com.cryptopilot.market;

import java.time.Instant;
import java.util.Objects;

/**
 * One settlement instant a pair's mark price snapshots announced as the next funding time, and the first and last
 * snapshot that announced it. Two announcements in a row whose snapshots are not separated by a {@link SnapshotGap}
 * show the pair's funding interval as the distance between their instants.
 *
 * <p>Rule: BR-11, BR-37; A-05.
 *
 * @param fundingTime the announced instant, rounded to the minute as a settlement is stored
 * @param firstSeen the earliest snapshot that announced it
 * @param lastSeen the latest snapshot that announced it
 */
public record FundingAnnouncement(Instant fundingTime, Instant firstSeen, Instant lastSeen) {

    public FundingAnnouncement {
        Objects.requireNonNull(fundingTime, "fundingTime");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException("lastSeen " + lastSeen + " is before firstSeen " + firstSeen);
        }
    }
}
