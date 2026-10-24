package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The funding of a Futures position over the time it was open: the sum of the settlements known so far, and whether
 * that is all of them. An incomplete funding never blocks a close; its amount is the known part only, and it names
 * the settlement instants that are missing when they can be told.
 *
 * <p>Rule: BR-37; A-05; D-97 note of 2026-10-24.
 *
 * @param amount the signed sum of the stored settlements of the period at the stored scale, received positive and
 *     paid negative
 * @param incomplete whether a settlement of the period may be missing, so the amount is provisional
 * @param missingFundingTimes the settlement instants known to be missing, oldest first; empty when complete, and may
 *     be empty when incomplete because the funding times of the gap cannot be told
 */
public record FundingFee(BigDecimal amount, boolean incomplete, List<Instant> missingFundingTimes) {

    public FundingFee {
        Objects.requireNonNull(amount, "amount");
        missingFundingTimes = List.copyOf(Objects.requireNonNull(missingFundingTimes, "missingFundingTimes"));
        if (!incomplete && !missingFundingTimes.isEmpty()) {
            throw new IllegalArgumentException("a complete funding fee has no missing settlement");
        }
    }

    /** The funding of a period whose settlements are all stored. */
    public static FundingFee complete(BigDecimal amount) {
        return new FundingFee(amount, false, List.of());
    }

    /** The funding of a period with settlements that may not be stored yet: the known sum, provisional. */
    public static FundingFee partial(BigDecimal knownAmount, List<Instant> missingFundingTimes) {
        return new FundingFee(knownAmount, true, missingFundingTimes);
    }

    /** Whether every settlement of the period is stored, so {@link #amount} is final. */
    public boolean isComplete() {
        return !incomplete;
    }
}
