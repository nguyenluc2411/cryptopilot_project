package com.cryptopilot.market;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The funding of a Futures pair over an interval: the settlements stored so far, and the settlement instants the
 * exchange announced for the pair in the same interval. An announced instant without a stored settlement is one the
 * system has not read yet.
 *
 * <p>Rule: BR-37; A-05.
 *
 * @param settlements the stored settlements, oldest first
 * @param scheduledTimes the announced settlement instants, rounded to the minute as settlements are stored, oldest
 *     first and each once
 */
public record FundingHistory(List<SettledFunding> settlements, List<Instant> scheduledTimes) {

    public FundingHistory {
        settlements = List.copyOf(Objects.requireNonNull(settlements, "settlements"));
        scheduledTimes = List.copyOf(Objects.requireNonNull(scheduledTimes, "scheduledTimes"));
    }
}
