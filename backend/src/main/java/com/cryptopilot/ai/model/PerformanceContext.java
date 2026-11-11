package com.cryptopilot.ai.model;

import com.cryptopilot.journal.PerformanceDigest;
import java.util.Objects;

/**
 * The Trader's performance summary in the context (PREMIUM, BR-51): the aggregates of the closed simulated trades the
 * SCR-23 dashboard shows, with the D-111 learning metrics. A Trader without a closed trade gets no figures and a
 * message, so the model says there is nothing to review yet instead of reading zeros as a result.
 *
 * <p>Rule: BR-51, BR-62, BR-70; D-111.
 *
 * @param available whether a summary is included
 * @param message what the model should tell the Trader when it is not, else {@code null}
 * @param summary the aggregates, or {@code null} when not available
 */
public record PerformanceContext(boolean available, String message, PerformanceDigest summary) {

    public static final PerformanceContext NO_CLOSED_TRADES = new PerformanceContext(
            false, "The Trader has no closed simulated trade yet, so there is no performance to review.", null);

    /** The digest as the context carries it: none when it counts no trade. */
    public static PerformanceContext of(PerformanceDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return digest.trades() == 0 ? NO_CLOSED_TRADES : new PerformanceContext(true, null, digest);
    }
}
