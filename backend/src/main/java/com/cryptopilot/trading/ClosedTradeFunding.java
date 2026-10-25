package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The funding of a closed Futures trade as the journal stores it: the signed sum of the settlements known so far,
 * whether one may still be missing, and the instants known to be missing.
 *
 * <p>Rule: BR-37; A-05; D-97 note of 2026-10-24.
 *
 * @param amount the signed sum at the stored scale, received positive and paid negative
 * @param incomplete whether a settlement of the period may be missing
 * @param missingFundingTimes the settlement instants known to be missing, oldest first
 */
public record ClosedTradeFunding(BigDecimal amount, boolean incomplete, List<Instant> missingFundingTimes) {

    public ClosedTradeFunding {
        Objects.requireNonNull(amount, "amount");
        missingFundingTimes = List.copyOf(Objects.requireNonNull(missingFundingTimes, "missingFundingTimes"));
    }
}
