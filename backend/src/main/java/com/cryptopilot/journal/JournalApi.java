package com.cryptopilot.journal;

import java.util.UUID;

/**
 * What the {@code journal} module tells other modules about one Trader's closed trades.
 *
 * <p>Rule: BR-42, BR-51, BR-70; TECHNICAL_DESIGN 7.14; D-111.
 */
public interface JournalApi {

    /**
     * The Trader's own performance over every closed simulated trade, for the AI Assistant's context snapshot. No plan
     * feature is checked here: the caller gates the use it makes of it ({@code AI_PERFORMANCE_CONTEXT}, BR-62).
     */
    PerformanceDigest performanceDigest(UUID userId);
}
