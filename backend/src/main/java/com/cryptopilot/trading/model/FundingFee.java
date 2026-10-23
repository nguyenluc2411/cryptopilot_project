package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The funding of a Futures position over the time it was open, or the settlements that keep it from being known.
 * Never a partial sum: while a settlement is missing the amount is unknown, not the sum of the others.
 *
 * <p>Rule: BR-37; A-05.
 *
 * @param amount the signed funding at the stored scale, received positive and paid negative; {@code null} when
 *     incomplete
 * @param missingFundingTimes the announced settlements of the period that are not stored yet, oldest first; empty
 *     when complete
 */
public record FundingFee(BigDecimal amount, List<Instant> missingFundingTimes) {

    public FundingFee {
        missingFundingTimes = List.copyOf(Objects.requireNonNull(missingFundingTimes, "missingFundingTimes"));
        if ((amount == null) == missingFundingTimes.isEmpty()) {
            throw new IllegalArgumentException("a funding fee has an amount exactly when no settlement is missing");
        }
    }

    /** The funding of a period whose settlements are all stored. */
    public static FundingFee complete(BigDecimal amount) {
        return new FundingFee(Objects.requireNonNull(amount, "amount"), List.of());
    }

    /** The funding of a period with settlements not stored yet: no amount until they are. */
    public static FundingFee incomplete(List<Instant> missingFundingTimes) {
        return new FundingFee(null, missingFundingTimes);
    }

    /** Whether every settlement of the period is stored, so {@link #amount} is known. */
    public boolean isComplete() {
        return missingFundingTimes.isEmpty();
    }
}
