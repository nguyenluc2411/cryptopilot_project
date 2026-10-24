package com.cryptopilot.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One stored funding settlement of a Futures pair: the rate that was charged and the mark price it was charged on.
 *
 * <p>Rule: BR-37; A-05, A-12.
 *
 * @param fundingTime the settlement instant, rounded to the minute as it is stored
 * @param fundingRate the settled rate; positive when LONG pays SHORT
 * @param markPrice the mark price at the settlement
 */
public record SettledFunding(Instant fundingTime, BigDecimal fundingRate, BigDecimal markPrice) {

    public SettledFunding {
        Objects.requireNonNull(fundingTime, "fundingTime");
        Objects.requireNonNull(fundingRate, "fundingRate");
        Objects.requireNonNull(markPrice, "markPrice");
    }
}
