package com.cryptopilot.journal.model;

import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import java.time.Instant;
import java.util.UUID;

/**
 * The trades one statistic counts: the owner's closed trades with an exit in {@code [from, to)}. Every statistic of a
 * dashboard reads the same filter, so its figures describe the same trades.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-41, BR-42.
 *
 * @param userId the owner
 * @param from the first instant counted, or {@code null} from the first trade
 * @param to the first instant no longer counted
 * @param market the market, never {@code null}
 * @param source the source, never {@code null}
 * @param pairId the pair, or {@code null}
 * @param strategyId the strategy, or {@code null}
 */
public record PerformanceFilter(
        UUID userId,
        Instant from,
        Instant to,
        PerformanceMarket market,
        PerformanceSource source,
        UUID pairId,
        UUID strategyId) {}
