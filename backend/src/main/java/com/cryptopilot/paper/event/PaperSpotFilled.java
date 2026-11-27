package com.cryptopilot.paper.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A paper Spot order executed part or all of its quantity. Published inside the fill's transaction, for the journal
 * to record it in the same transaction (RF-35): a buy opens or adds to the record of its order, a sale closes the
 * Trader's open Spot records of the pair, oldest first.
 *
 * <p>Rule: BM04 F-06; D-117; RF-33, RF-35.
 *
 * @param userId the Trader
 * @param orderId the order
 * @param planId the plan the order carries out, or {@code null} for an order placed directly
 * @param pairId the pair
 * @param buy whether it bought
 * @param orderType the order's type, e.g. MARKET, LIMIT_MAKER (an OCO take profit) or STOP_LOSS
 * @param maker whether the fill rested in the book
 * @param price the fill price
 * @param quantity the base quantity executed
 * @param feeInQuote the fee, in the quote asset (a buy's fee, taken in the base coin, valued at the fill price)
 * @param at when it executed
 */
public record PaperSpotFilled(
        UUID userId,
        UUID orderId,
        UUID planId,
        UUID pairId,
        boolean buy,
        String orderType,
        boolean maker,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal feeInQuote,
        Instant at) {

    public PaperSpotFilled {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(feeInQuote, "feeInQuote");
        Objects.requireNonNull(at, "at");
    }
}
