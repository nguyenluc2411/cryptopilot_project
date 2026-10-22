package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.SymbolRules;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;

/**
 * The exchange's Spot symbol filters a paper order is checked against when it is placed, a single order or each order
 * of a group: a figure that breaks one is refused, never rounded, as the exchange refuses it with a filter failure.
 * A rule the pair does not have ({@code null}) is not checked.
 *
 * <p>Each refusal is a {@link BusinessException} whose message arguments are the field, its value, the limit it broke
 * and which limit: {@link #MIN}, {@link #MAX} or {@link #STEP}.
 *
 * <p>Rule: TR-02, TR-09, TR-11; BR-23, BR-30.
 *
 * <p>Reference: Binance Spot API documentation, "Filters" (PRICE_FILTER, LOT_SIZE, MARKET_LOT_SIZE, NOTIONAL,
 * PERCENT_PRICE_BY_SIDE, TRAILING_DELTA) and "Trailing Stop FAQ".
 */
final class SpotFilters {

    /** The value is below the limit. */
    static final String MIN = "MIN";

    /** The value is above the limit. */
    static final String MAX = "MAX";

    /** The value is not a multiple of the limit, counted from the rule's minimum. */
    static final String STEP = "STEP";

    /** The smallest quantity a balance holds: 8 decimals. */
    private static final BigDecimal BALANCE_STEP = BigDecimal.ONE.movePointLeft(Rounding.AMOUNT_SCALE);

    private SpotFilters() {}

    /** {@code PRICE_FILTER}: a price or stopPrice within [minPrice, maxPrice], on the tick counted from minPrice. */
    static void requirePrice(PairFilters filters, BigDecimal price, String field) {
        SymbolRules rules = filters.rules();
        requireWithin(ErrorCode.PAPER_PRICE_FILTER, field, price, rules.minPrice(), rules.maxPrice());
        requireStep(ErrorCode.PAPER_PRICE_FILTER, field, price, rules.minPrice(), filters.tickSize());
    }

    /**
     * {@code LOT_SIZE}: a quantity within [minQty, maxQty], on the step counted from minQty, with no more decimals than
     * a balance holds.
     */
    static void requireQuantity(PairFilters filters, BigDecimal quantity, String field) {
        SymbolRules rules = filters.rules();
        requireWithin(ErrorCode.PAPER_LOT_SIZE, field, quantity, rules.minQuantity(), rules.maxQuantity());
        requireStep(ErrorCode.PAPER_LOT_SIZE, field, quantity, rules.minQuantity(), filters.stepSize());
        requireStep(ErrorCode.PAPER_LOT_SIZE, field, quantity, null, BALANCE_STEP);
    }

    /**
     * A MARKET order the book gives nothing to: a {@code quoteOrderQty} worth less than one step, or no quantity at
     * the levels. Refused as {@code LOT_SIZE}, below one step or the minimum quantity.
     */
    static void requirePositive(PairFilters filters, BigDecimal quantity, String field) {
        if (quantity.signum() == 0) {
            BigDecimal min = filters.rules().minQuantity();
            throw refused(
                    ErrorCode.PAPER_LOT_SIZE, field, quantity, min != null ? min : filters.stepSize(), MIN, "below");
        }
    }

    /**
     * {@code MARKET_LOT_SIZE}, on top of {@code LOT_SIZE}: a MARKET order's quantity within its [minQty, maxQty], and
     * on its step when it has one and the quantity is the Trader's.
     *
     * @param given whether the Trader gave the quantity; one derived from {@code quoteOrderQty} is on the step already
     */
    static void requireMarketQuantity(PairFilters filters, BigDecimal quantity, String field, boolean given) {
        SymbolRules rules = filters.rules();
        if (!given) {
            requireWithin(ErrorCode.PAPER_LOT_SIZE, field, quantity, rules.minQuantity(), rules.maxQuantity());
        }
        requireWithin(
                ErrorCode.PAPER_MARKET_LOT_SIZE, field, quantity, rules.marketMinQuantity(), rules.marketMaxQuantity());
        if (given && rules.marketStepSize() != null) {
            requireStep(
                    ErrorCode.PAPER_MARKET_LOT_SIZE,
                    field,
                    quantity,
                    rules.marketMinQuantity(),
                    rules.marketStepSize());
        }
    }

    /**
     * {@code NOTIONAL}: what an order is worth within [minNotional, maxNotional]. A MARKET order is checked against
     * each bound only when the pair applies it to MARKET orders, worth what its sweep of the book trades, at its
     * average price.
     */
    static void requireNotional(PairFilters filters, BigDecimal notional, boolean market, String field) {
        SymbolRules rules = filters.rules();
        BigDecimal min = !market || rules.minAppliesToMarket() ? filters.minNotional() : null;
        BigDecimal max = !market || rules.maxAppliesToMarket() ? rules.maxNotional() : null;
        requireWithin(ErrorCode.PAPER_NOTIONAL, field, notional, min, max);
    }

    /**
     * {@code PERCENT_PRICE_BY_SIDE}: a limit price within the reference price times the multipliers of its side, the
     * bid multipliers for a BUY and the ask multipliers for a SELL. The reference is the current last price; without
     * one nothing is checked.
     */
    static void requirePercentPrice(
            PairFilters filters, OrderSide side, BigDecimal price, BigDecimal reference, String field) {
        if (reference == null) {
            return;
        }
        SymbolRules rules = filters.rules();
        boolean buy = side == OrderSide.BUY;
        BigDecimal up = buy ? rules.bidMultiplierUp() : rules.askMultiplierUp();
        BigDecimal down = buy ? rules.bidMultiplierDown() : rules.askMultiplierDown();
        requireWithin(
                ErrorCode.PAPER_PERCENT_PRICE,
                field,
                price,
                down == null ? null : reference.multiply(down),
                up == null ? null : reference.multiply(up));
    }

    /**
     * {@code TRAILING_DELTA}: a trailing delta, in basis points, within the pair's range for the side its stop
     * triggers on: the "above" range for a STOP_LOSS or STOP_LOSS_LIMIT BUY and a TAKE_PROFIT or TAKE_PROFIT_LIMIT SELL,
     * whose condition is met at or above the stop, the "below" range for the other four.
     */
    static void requireTrailingDelta(PairFilters filters, TriggerCondition condition, long delta, String field) {
        SymbolRules rules = filters.rules();
        boolean above = condition == TriggerCondition.AT_OR_ABOVE;
        Integer min = above ? rules.minTrailingAboveDelta() : rules.minTrailingBelowDelta();
        Integer max = above ? rules.maxTrailingAboveDelta() : rules.maxTrailingBelowDelta();
        requireWithin(
                ErrorCode.PAPER_TRAILING_DELTA,
                field,
                BigDecimal.valueOf(delta),
                min == null ? null : BigDecimal.valueOf(min),
                max == null ? null : BigDecimal.valueOf(max));
    }

    private static void requireWithin(ErrorCode code, String field, BigDecimal value, BigDecimal min, BigDecimal max) {
        if (min != null && value.compareTo(min) < 0) {
            throw refused(code, field, value, min, MIN, "below");
        }
        if (max != null && value.compareTo(max) > 0) {
            throw refused(code, field, value, max, MAX, "above");
        }
    }

    /** On the grid of {@code step} counted from {@code from}, as the exchange's {@code (value - min) % step == 0}. */
    private static void requireStep(ErrorCode code, String field, BigDecimal value, BigDecimal from, BigDecimal step) {
        BigDecimal offset = from == null ? value : value.subtract(from);
        if (offset.remainder(step).signum() != 0) {
            throw refused(code, field, value, step, STEP, "off the step of");
        }
    }

    private static BusinessException refused(
            ErrorCode code, String field, BigDecimal value, BigDecimal limit, String bound, String relation) {
        String given = value.stripTrailingZeros().toPlainString();
        String shown = limit.stripTrailingZeros().toPlainString();
        return new BusinessException(
                code,
                field + " " + given + " is " + relation + " " + shown + " (" + code.code() + ")",
                field,
                given,
                shown,
                bound);
    }
}
