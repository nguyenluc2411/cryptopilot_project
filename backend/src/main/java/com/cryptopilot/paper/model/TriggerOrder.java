package com.cryptopilot.paper.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A working Spot order that waits for the last price to trigger it, as the paper matching engine tracks it: a stop
 * limit at a fixed stop (TR-09), or a trailing stop whose stop follows the best price since it was activated (TR-11).
 * Triggered, it becomes the limit order it carries.
 *
 * <p>Rule: TR-09, TR-11; D-77.
 *
 * @param orderId the order
 * @param accountId its account, whose Trader's lock the trigger is made under
 * @param market the market
 * @param pairId the pair
 * @param condition which way the price must move
 * @param stopPrice the fixed stop of a stop limit; {@code null} for a trailing stop
 * @param callbackRate a trailing stop's callback, in percent of the best price; {@code null} for a stop limit
 * @param activationPrice the price that activates a trailing stop; {@code null} when it is active from its placement
 * @param extreme a trailing stop's best price so far: the highest for one that triggers below, the lowest for one that
 *     triggers above; {@code null} before it is activated
 * @param placedAt when it was placed; a price traded before it never moves or triggers it (D-77)
 */
public record TriggerOrder(
        UUID orderId,
        UUID accountId,
        MarketType market,
        UUID pairId,
        TriggerCondition condition,
        BigDecimal stopPrice,
        BigDecimal callbackRate,
        BigDecimal activationPrice,
        BigDecimal extreme,
        Instant placedAt) {

    public TriggerOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(placedAt, "placedAt");
        if ((stopPrice == null) == (callbackRate == null)) {
            throw new IllegalArgumentException("a trigger order has either a stop price or a callback rate");
        }
        if (callbackRate != null && activationPrice == null && extreme == null) {
            throw new IllegalArgumentException("a trailing stop without an activation price starts from a best price");
        }
    }

    /** Whether the stop follows the price. */
    public boolean trailing() {
        return callbackRate != null;
    }
}
