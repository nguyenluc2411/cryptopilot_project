package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A position and how it closed: what its realized profit and loss and its fees are calculated from.
 *
 * <p>Rule: BR-35, BR-36; D-97.
 *
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param entryType how the position was entered, which sets the entry fee rate
 * @param entryPrice the entry fill price
 * @param quantity the filled quantity, above 0
 * @param reason why the position closed
 * @param exitPrice the exit fill price; the liquidation price for a liquidation
 * @param fees the fee rates of the market
 * @param brackets the leverage brackets of the pair; used by a liquidation only, may be empty otherwise. The
 *     bracket of the entry notional sets the maintenance margin that caps the clearance fee (Q-58)
 */
public record TradeClose(
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal quantity,
        CloseReason reason,
        BigDecimal exitPrice,
        FeeSchedule fees,
        List<LeverageBracket> brackets) {

    public TradeClose {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(exitPrice, "exitPrice");
        Objects.requireNonNull(fees, "fees");
        brackets = List.copyOf(brackets);
        if (quantity.signum() <= 0 || entryPrice.signum() <= 0 || exitPrice.signum() <= 0) {
            throw new IllegalArgumentException("quantity, entry price and exit price must be above 0, were " + quantity
                    + ", " + entryPrice + ", " + exitPrice);
        }
        if (reason == CloseReason.LIQUIDATION) {
            if (market != MarketType.FUTURES) {
                throw new IllegalArgumentException("only a Futures position is liquidated");
            }
            if (fees.liquidationClearance() == null || brackets.isEmpty()) {
                throw new IllegalArgumentException("a liquidation needs the clearance fee rate and the brackets");
            }
        }
    }
}
