package com.cryptopilot.trading.event;

import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A plan's entry filled with a setup score at least {@code SETUP_SCORE_DROP_WARN} points below the score at its
 * activation. Published inside the fill's transaction, beside {@link PlanFilled}, so only a fill that commits tells the
 * Trader, once (MSG55). It only informs: the fill has already happened and is never held back.
 *
 * <p>Rule: BR-70; MSG55; D-111 (M3), D-112.
 *
 * @param planId the plan
 * @param positionId the journal record the fill opened
 * @param userId the owner
 * @param direction LONG or SHORT
 * @param symbol the pair's symbol, or {@code null} when it could not be read
 * @param scoreAtPlan the setup score at activation
 * @param scoreAtFill the setup score at the fill
 * @param filledAt the fill time (D-78)
 */
public record SetupScoreDropped(
        UUID planId,
        UUID positionId,
        UUID userId,
        Direction direction,
        String symbol,
        BigDecimal scoreAtPlan,
        BigDecimal scoreAtFill,
        Instant filledAt) {}
