package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The plan values the risk calculation does not read: how the entry is placed, when an unfilled plan expires, when it
 * stops being valid, the timeframe it was analysed on and the Trader's note.
 *
 * <p>Rule: SRS 3.5.1; BR-32 (expiry), BR-68, BR-69, BR-70; D-111, D-112.
 *
 * @param entryType LIMIT, MARKET or STOP
 * @param expiresAt when an ACTIVE plan that has not been filled expires; {@code null} for none
 * @param note the Trader's note; {@code null} for none
 * @param invalidationPrice the price that invalidates a waiting LIMIT or STOP plan; {@code null} for none
 * @param maxEntryDeviationR the plan's own limit on the entry deviation, in R; {@code null} for the system's
 * @param analysisTimeframe the timeframe the plan was analysed on; required, already defaulted by the caller
 */
public record PlanDetails(
        EntryType entryType,
        Instant expiresAt,
        String note,
        BigDecimal invalidationPrice,
        BigDecimal maxEntryDeviationR,
        AnalysisTimeframe analysisTimeframe) {

    public PlanDetails {
        Objects.requireNonNull(entryType, "entryType");
    }
}
