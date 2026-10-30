package com.cryptopilot.paper.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something a Trader must be told about their paper Futures risk: a margin call, or a position liquidated (TR-43); a
 * held position near its liquidation price, or held without a stop loss (TR-27).
 * Published after the change commits; the notification module writes the in-app notice from it.
 *
 * <p>Rule: TR-27, TR-43; NSF-15.
 *
 * @param kind what happened
 * @param symbol the pair, or {@code null} for a margin call of the cross positions together
 * @param marginType {@code ISOLATED} or {@code CROSSED}
 * @param positionId the position, or {@code null} for a margin call of the cross positions together
 * @param price the mark that reached the margin call or came near the liquidation price, or the price of the
 *     liquidation; {@code null} for a cross call and for a missing stop loss
 * @param marginRatio the margin ratio of a margin call; otherwise {@code null}
 * @param at when it happened
 * @param liquidationPrice the liquidation price the mark came near; otherwise {@code null}
 */
public record FuturesRiskNotice(
        UUID userId,
        Kind kind,
        String symbol,
        String marginType,
        UUID positionId,
        BigDecimal price,
        BigDecimal marginRatio,
        Instant at,
        BigDecimal liquidationPrice) {

    public FuturesRiskNotice {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(marginType, "marginType");
        Objects.requireNonNull(at, "at");
    }

    /** A notice without a liquidation price: a margin call, a liquidation, a missing stop loss. */
    public FuturesRiskNotice(
            UUID userId,
            Kind kind,
            String symbol,
            String marginType,
            UUID positionId,
            BigDecimal price,
            BigDecimal marginRatio,
            Instant at) {
        this(userId, kind, symbol, marginType, positionId, price, marginRatio, at, null);
    }

    /** What the Trader is told about. */
    public enum Kind {
        /** The margin ratio reached {@code margin-call-ratio}. */
        MARGIN_CALL,

        /** A position was liquidated. */
        LIQUIDATION,

        /** The mark came within {@code near-liquidation-percent} of a held position's liquidation price (TR-27). */
        NEAR_LIQUIDATION,

        /** A position was held {@code stop-loss-grace} without a stop loss (TR-27). */
        NO_STOP_LOSS
    }
}
