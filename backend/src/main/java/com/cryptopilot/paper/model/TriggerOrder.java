package com.cryptopilot.paper.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A working Spot stop (STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT) as the paper matching engine
 * watches it, with the exchange's stopPrice, trailingDelta or both (TR-09, TR-11).
 *
 * <ul>
 *   <li>A stopPrice alone: triggers when the last price trades at or past it on the side of {@code condition}.
 *   <li>A trailing delta: tracks the best price — the highest for a SELL, the lowest for a BUY — and triggers when the
 *       price comes back by the delta. With a stopPrice, tracking starts at the first trade that meets the stop
 *       condition; without one, at the first trade after the placement.
 * </ul>
 *
 * <p>Rule: TR-09, TR-11; D-77.
 *
 * @param orderId the order
 * @param accountId its account, whose Trader's lock the trigger is made under
 * @param market the market
 * @param pairId the pair
 * @param side BUY or SELL: which way a trailing stop tracks and comes back
 * @param condition the side of the last price the stop condition is met on
 * @param stopPrice the exchange's stopPrice: the trigger, or the activation of a trailing stop; may be absent on one
 * @param trailingPercent the trailing delta in percent of the best price; {@code null} for a plain stop
 * @param extreme a trailing stop's best price so far; {@code null} before it tracks
 * @param placedAt when it was placed; a price traded before it never moves or triggers it (D-77)
 */
public record TriggerOrder(
        UUID orderId,
        UUID accountId,
        MarketType market,
        UUID pairId,
        OrderSide side,
        TriggerCondition condition,
        BigDecimal stopPrice,
        BigDecimal trailingPercent,
        BigDecimal extreme,
        Instant placedAt) {

    public TriggerOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(placedAt, "placedAt");
        if (stopPrice == null && trailingPercent == null) {
            throw new IllegalArgumentException("a stop has a stop price, a trailing delta or both");
        }
    }

    /** Whether the stop tracks the price. */
    public boolean trailing() {
        return trailingPercent != null;
    }
}
