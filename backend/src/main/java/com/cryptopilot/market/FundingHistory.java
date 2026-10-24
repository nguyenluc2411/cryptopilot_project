package com.cryptopilot.market;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The funding of a Futures pair over an interval: the settlements stored so far, the settlement instants the exchange
 * announced in the same interval, and what the funding times of the pair can be checked against when an announcement
 * is missing: every announcement and every gap of the mark price snapshots over the interval and a look-back window
 * before it, and the settlements stored in that window.
 *
 * <p>Rule: BR-11, BR-37; A-05.
 *
 * @param settlements the stored settlements of the interval, oldest first
 * @param scheduledTimes the announced settlement instants of the interval, rounded to the minute as settlements are
 *     stored, oldest first and each once
 * @param announcements every announced instant of the window, with the snapshots that announced it, oldest first
 * @param snapshotGaps the stretches of the window without snapshots, oldest first
 * @param earlierSettlementTimes the instants of the settlements stored in the look-back window before the interval,
 *     oldest first
 */
public record FundingHistory(
        List<SettledFunding> settlements,
        List<Instant> scheduledTimes,
        List<FundingAnnouncement> announcements,
        List<SnapshotGap> snapshotGaps,
        List<Instant> earlierSettlementTimes) {

    public FundingHistory {
        settlements = List.copyOf(Objects.requireNonNull(settlements, "settlements"));
        scheduledTimes = List.copyOf(Objects.requireNonNull(scheduledTimes, "scheduledTimes"));
        announcements = List.copyOf(Objects.requireNonNull(announcements, "announcements"));
        snapshotGaps = List.copyOf(Objects.requireNonNull(snapshotGaps, "snapshotGaps"));
        earlierSettlementTimes = List.copyOf(Objects.requireNonNull(earlierSettlementTimes, "earlierSettlementTimes"));
    }
}
