package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Where an isolated Futures position is liquidated, as the matching engine watches it (TR-22): a long when the mark is
 * at or below the price, a short when it is at or above. Only for screening: the liquidation reads the position again
 * under the Trader's lock and decides on the database.
 *
 * <p>The margin call price, where the margin ratio reaches {@code margin-call-ratio}, is {@code null} when not known
 * (TR-43).
 *
 * <p>Rule: TR-22, TR-43; D-127.
 *
 * @param positionId the position
 * @param userId its Trader
 * @param pairId its pair
 * @param direction long or short
 * @param price the liquidation price; zero when no price can liquidate it
 */
public record LiquidationLevel(
        UUID positionId,
        UUID userId,
        UUID pairId,
        PositionDirection direction,
        BigDecimal price,
        BigDecimal marginCallPrice) {

    public LiquidationLevel {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(price, "price");
    }

    /** A level without a margin call price. */
    public LiquidationLevel(UUID positionId, UUID userId, UUID pairId, PositionDirection direction, BigDecimal price) {
        this(positionId, userId, pairId, direction, price, null);
    }

    /** Whether the mark reached the level. */
    public boolean reachedBy(BigDecimal mark) {
        return reached(price, mark);
    }

    /** Whether the mark reached the margin call price but not the liquidation price (TR-43). */
    public boolean marginCallReachedBy(BigDecimal mark) {
        return marginCallPrice != null && reached(marginCallPrice, mark) && !reachedBy(mark);
    }

    private boolean reached(BigDecimal level, BigDecimal mark) {
        if (level.signum() <= 0) {
            return false;
        }
        int side = mark.compareTo(level);
        return direction == PositionDirection.LONG ? side <= 0 : side >= 0;
    }
}
