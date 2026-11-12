package com.cryptopilot.journal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the {@code journal} module tells other modules about the learning data of one Trader's simulated trades, e.g.
 * the AI Assistant's context. Only the Trader's own trades are read, and only SIMULATED ones: a manual trade has no
 * fill the system saw. No plan feature is checked here; the caller gates the use it makes of it.
 *
 * <p>Rule: BR-41, BR-70; D-111 (M4).
 */
public interface FillLearningReader {

    /** The learning data of one of the user's simulated trades; empty for an unknown id, another's or a manual trade. */
    Optional<FillLearning> ofTrade(UUID userId, UUID journalId);

    /**
     * The learning data of the user's latest closed simulated trades, the latest exit first.
     *
     * @param limit how many at most, from 1 to 100
     * @throws IllegalArgumentException for a limit outside 1..100
     */
    List<FillLearning> latestClosed(UUID userId, int limit);
}
