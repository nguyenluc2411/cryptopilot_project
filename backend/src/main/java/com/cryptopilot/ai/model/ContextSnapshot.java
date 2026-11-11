package com.cryptopilot.ai.model;

import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.trading.PlanContext;
import java.time.Instant;

/**
 * The data a question is asked with (T-074, BR-51), stored in {@code ai_message.context_snapshot} and sent to the AI
 * Service as the question's context. It holds market data and the plan's terms and ratios only: no email, no name,
 * no user id, no capital and no free text the Trader wrote, so nothing personal reaches the LLM Provider.
 *
 * <p>Rule: BR-51; SRS 3.9.1; TECHNICAL_DESIGN 7.14 step 1.
 *
 * @param version the shape of the snapshot, raised when a field changes meaning
 * @param builtAt when it was built
 * @param market the pair's analysis, or {@code null} when no pair was chosen
 * @param plan the chosen plan, or {@code null}
 * @param performance the performance summary, or {@code null} when not asked for
 */
public record ContextSnapshot(
        int version, Instant builtAt, PairAnalysis market, PlanContext plan, PerformanceContext performance) {

    /**
     * 3: the market part carries the side the setup score was computed for and its cap reason (D-146); 2: the
     * performance summary carries the journal's aggregates (T-074, D-111).
     */
    public static final int VERSION = 3;

    /** Whether there is nothing to send: a general question carries no snapshot. Not a bean getter, so not serialized. */
    public boolean carriesNothing() {
        return market == null && plan == null && performance == null;
    }
}
