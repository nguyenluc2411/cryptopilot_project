package com.cryptopilot.paper.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A paper Futures position was closed: by its orders, by a liquidation, or by the system (plan 9.1 step 8). Published
 * inside the transaction that closed it, for whoever follows a position's life after the commit: the position
 * warnings forget it (TR-27), and the journal may listen.
 *
 * <p>Rule: TR-27; D-127.
 *
 * @param userId the Trader
 * @param positionId the position
 * @param pairId its pair
 * @param closeReason {@code MANUAL}, {@code LIQUIDATION} or {@code SYSTEM}
 * @param realizedPnl what it realised over its life, funding and fees apart
 * @param closedAt when it closed
 */
public record PaperPositionClosed(
        UUID userId, UUID positionId, UUID pairId, String closeReason, BigDecimal realizedPnl, Instant closedAt) {

    public PaperPositionClosed {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(closeReason, "closeReason");
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(closedAt, "closedAt");
    }
}
