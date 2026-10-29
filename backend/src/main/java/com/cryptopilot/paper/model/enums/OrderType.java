package com.cryptopilot.paper.model.enums;

import java.util.Set;

/**
 * The kind of a paper order: on Spot the order types of the exchange's Spot API, on Futures those of its Futures API.
 * The names are the values {@code trade_order.order_type} accepts. {@code TAKE_PROFIT} names a take profit on both
 * markets; it executes as a MARKET order on Spot and as a LIMIT order on Futures, as on the exchange. The Futures kinds
 * are placed by a later stage of TR-02, so a request for one is refused for now.
 *
 * <p>Rule: TR-02, TR-09, TR-11, TR-12.
 */
public enum OrderType {

    /** Executes at once at the current price. */
    MARKET,

    /** Waits at its price, or executes at once when the price is already there. */
    LIMIT,

    /** Only ever waits at its price: refused when it would execute on arrival (TR-12). Has no time in force. */
    LIMIT_MAKER,

    /** Spot: once its stop condition is met, executes as a MARKET order. Above the price for a BUY, below for a SELL. */
    STOP_LOSS,

    /** Spot: once its stop condition is met, works as a LIMIT order. Above the price for a BUY, below for a SELL. */
    STOP_LOSS_LIMIT,

    /**
     * Spot: once its stop condition is met, executes as a MARKET order; below the price for a BUY, above for a SELL.
     * Futures: a take profit that works as a LIMIT order.
     */
    TAKE_PROFIT,

    /** Spot: once its stop condition is met, works as a LIMIT order. Below the price for a BUY, above for a SELL. */
    TAKE_PROFIT_LIMIT,

    /** Futures: a stop that works as a LIMIT order. */
    STOP,

    /** Futures: a stop that executes as a MARKET order. */
    STOP_MARKET,

    /** Futures: a take profit that executes as a MARKET order. */
    TAKE_PROFIT_MARKET,

    /** Futures: a trailing stop that executes as a MARKET order. */
    TRAILING_STOP_MARKET;

    /** The Spot kinds that wait for the last price to trigger them, with a stopPrice, a trailing delta or both. */
    public static final Set<OrderType> SPOT_STOPS = Set.of(STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT);

    /** Whether a Spot order of this kind waits for a trigger. */
    /** The conditional orders of paper Futures that TR-20 takes: STOP, STOP_MARKET, TAKE_PROFIT, TAKE_PROFIT_MARKET. */
    public static final Set<OrderType> FUTURES_CONDITIONAL = Set.of(STOP, STOP_MARKET, TAKE_PROFIT, TAKE_PROFIT_MARKET);

    /** Whether the type is a Futures conditional order, which waits for a trigger before it places an order. */
    public boolean isFuturesConditional() {
        return FUTURES_CONDITIONAL.contains(this);
    }

    /** Whether a Futures conditional order of this type places a MARKET order once triggered. */
    public boolean isFuturesMarketConditional() {
        return this == STOP_MARKET || this == TAKE_PROFIT_MARKET;
    }

    /**
     * The side of the price a Futures conditional order of this type and side triggers on (plan 5.4): a stop BUY and
     * a take-profit SELL at or above the trigger, a stop SELL and a take-profit BUY at or below it.
     */
    public TriggerCondition futuresTriggerConditionFor(OrderSide side) {
        boolean stop = this == STOP || this == STOP_MARKET;
        boolean above = stop == (side == OrderSide.BUY);
        return above ? TriggerCondition.AT_OR_ABOVE : TriggerCondition.AT_OR_BELOW;
    }

    public boolean isSpotStop() {
        return SPOT_STOPS.contains(this);
    }

    /** Whether a Spot stop of this kind executes as a MARKET order once triggered. */
    public boolean isSpotMarketStop() {
        return this == STOP_LOSS || this == TAKE_PROFIT;
    }

    /**
     * The side of the last price on which a Spot stop of this kind is triggered, as the exchange fixes it: a stop loss
     * BUY and a take profit SELL at or above their stop, a stop loss SELL and a take profit BUY at or below it.
     */
    public TriggerCondition triggerConditionFor(OrderSide side) {
        boolean stopLoss = this == STOP_LOSS || this == STOP_LOSS_LIMIT;
        boolean above = stopLoss == (side == OrderSide.BUY);
        return above ? TriggerCondition.AT_OR_ABOVE : TriggerCondition.AT_OR_BELOW;
    }
}
