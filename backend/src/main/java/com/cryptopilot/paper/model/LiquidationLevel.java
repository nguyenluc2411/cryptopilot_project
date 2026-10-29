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
 * <p>Rule: TR-22; D-127.
 *
 * @param positionId the position
 * @param userId its Trader
 * @param pairId its pair
 * @param direction long or short
 * @param price the liquidation price; zero when no price can liquidate it
 */
public record LiquidationLevel(
        UUID positionId, UUID userId, UUID pairId, PositionDirection direction, BigDecimal price) {

    public LiquidationLevel {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(price, "price");
    }

    /** Whether the mark reached the level. */
    public boolean reachedBy(BigDecimal mark) {
        if (price.signum() <= 0) {
            return false;
        }
        int side = mark.compareTo(price);
        return direction == PositionDirection.LONG ? side <= 0 : side >= 0;
    }
}
