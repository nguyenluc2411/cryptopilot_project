package com.cryptopilot.paper.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something a Trader must be told about their paper Futures risk (TR-43): a margin call, or a position liquidated.
 * Published after the change commits; the notification module writes the in-app notice from it.
 *
 * <p>Rule: TR-43; NSF-15.
 *
 * @param kind what happened
 * @param symbol the pair, or {@code null} for a margin call of the cross positions together
 * @param marginType {@code ISOLATED} or {@code CROSSED}
 * @param positionId the position, or {@code null} for a margin call of the cross positions together
 * @param price the mark that reached the margin call, or the price of the liquidation; {@code null} for a cross call
 * @param marginRatio the margin ratio of a margin call; {@code null} for a liquidation
 * @param at when it happened
 */
public record FuturesRiskNotice(
        UUID userId,
        Kind kind,
        String symbol,
        String marginType,
        UUID positionId,
        BigDecimal price,
        BigDecimal marginRatio,
        Instant at) {

    public FuturesRiskNotice {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(marginType, "marginType");
        Objects.requireNonNull(at, "at");
    }

    /** What the Trader is told about. */
    public enum Kind {
        /** The margin ratio reached {@code margin-call-ratio}. */
        MARGIN_CALL,

        /** A position was liquidated. */
        LIQUIDATION
    }
}
