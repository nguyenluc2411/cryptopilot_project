package com.cryptopilot.paper.model;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The cross side of a Trader's Futures account as the matching engine screens it on each mark (TR-23, plan 9.3): the
 * cross wallet and the open cross positions. A snapshot, read again whenever a committed change of the account is
 * reported; a liquidation it suggests is decided again on the database.
 *
 * <p>Rule: TR-23; D-127.
 *
 * @param crossWalletBalance the Futures wallet less every isolated wallet; may be negative
 * @param legs the open cross positions, never empty
 */
public record CrossAccount(UUID userId, BigDecimal crossWalletBalance, List<Leg> legs) {

    public CrossAccount {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(crossWalletBalance, "crossWalletBalance");
        legs = List.copyOf(legs);
    }

    /**
     * One open cross position.
     *
     * @param tiers the leverage brackets of its pair
     */
    public record Leg(
            UUID pairId,
            PositionDirection direction,
            BigDecimal quantity,
            BigDecimal entryPrice,
            List<LeverageTier> tiers) {

        public Leg {
            Objects.requireNonNull(pairId, "pairId");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(entryPrice, "entryPrice");
            tiers = List.copyOf(tiers);
        }
    }
}
