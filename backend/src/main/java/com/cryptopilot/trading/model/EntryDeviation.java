package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * How far the expected fill of a MARKET entry has moved from the plan, in R, and what the activation does with it.
 *
 * <p>Rule: BR-69; D-111 (M2), D-112 (R7).
 *
 * @param deviationR the deviation in R at the scale it is recorded with; positive is a worse entry
 * @param limitR the largest deviation the activation accepts: the plan's own limit when it is stricter, otherwise
 *     {@code ENTRY_DEVIATION_BLOCK_R}
 * @param takeProfitReached whether the expected fill is at or past the take profit
 * @param verdict what the activation does
 */
public record EntryDeviation(BigDecimal deviationR, BigDecimal limitR, boolean takeProfitReached, Verdict verdict) {

    public EntryDeviation {
        Objects.requireNonNull(deviationR, "deviationR");
        Objects.requireNonNull(limitR, "limitR");
        Objects.requireNonNull(verdict, "verdict");
        if (takeProfitReached && verdict != Verdict.REFUSE) {
            throw new IllegalArgumentException("an entry at or past the take profit is refused");
        }
    }

    /** What an activation does with the deviation. */
    public enum Verdict {
        /** Below the confirm value, or a better price: the activation goes on. */
        PROCEED,
        /** From the confirm value up to and including the limit: only once the Trader confirms (MSG51). */
        CONFIRM,
        /** Above the limit, or at or past the take profit: never (MSG52). */
        REFUSE
    }
}
