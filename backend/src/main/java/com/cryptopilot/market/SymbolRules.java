package com.cryptopilot.market;

import java.math.BigDecimal;

/**
 * The exchange's symbol filters beyond the tick, the step and the minimum notional of {@link PairFilters}: the bounds a
 * Spot order is checked against when it is placed (TR-02). Each figure is the exchange's, read by the symbol
 * synchronisation (NSF-01); {@code null} means the rule is off, as the exchange means by {@code 0}.
 *
 * <p>A value object compared by value: decimals are held without trailing zeros, so {@code 5.00000000} from the
 * exchange equals {@code 5.000000000000} read back from the database.
 *
 * <p>Rule: TR-02; NSF-01; BR-23, BR-30.
 *
 * <p>Reference: Binance Spot API documentation, "Filters" (PRICE_FILTER, LOT_SIZE, MARKET_LOT_SIZE, NOTIONAL,
 * PERCENT_PRICE_BY_SIDE, TRAILING_DELTA, MAX_NUM_ORDERS, MAX_NUM_ALGO_ORDERS).
 *
 * @param minPrice the lowest price ({@code PRICE_FILTER.minPrice})
 * @param maxPrice the highest price ({@code PRICE_FILTER.maxPrice})
 * @param minQuantity the smallest quantity ({@code LOT_SIZE.minQty})
 * @param maxQuantity the largest quantity ({@code LOT_SIZE.maxQty})
 * @param marketMinQuantity the smallest quantity of a MARKET order ({@code MARKET_LOT_SIZE.minQty})
 * @param marketMaxQuantity the largest quantity of a MARKET order ({@code MARKET_LOT_SIZE.maxQty})
 * @param marketStepSize the quantity increment of a MARKET order ({@code MARKET_LOT_SIZE.stepSize})
 * @param applyMinToMarket whether the minimum notional applies to MARKET orders ({@code NOTIONAL.applyMinToMarket});
 *     {@code null} when not sent, read as {@code true} as the older {@code MIN_NOTIONAL.applyToMarket} defaults
 * @param maxNotional the largest order value ({@code NOTIONAL.maxNotional})
 * @param applyMaxToMarket whether the maximum notional applies to MARKET orders ({@code NOTIONAL.applyMaxToMarket});
 *     {@code null} when not sent, read as {@code false}
 * @param bidMultiplierUp a BUY's price at most the reference price times this ({@code PERCENT_PRICE_BY_SIDE})
 * @param bidMultiplierDown a BUY's price at least the reference price times this
 * @param askMultiplierUp a SELL's price at most the reference price times this
 * @param askMultiplierDown a SELL's price at least the reference price times this
 * @param minTrailingAboveDelta the smallest trailing delta, in basis points, of a stop triggered above the price
 *     ({@code TRAILING_DELTA})
 * @param maxTrailingAboveDelta the largest trailing delta of a stop triggered above the price
 * @param minTrailingBelowDelta the smallest trailing delta of a stop triggered below the price
 * @param maxTrailingBelowDelta the largest trailing delta of a stop triggered below the price
 * @param maxNumOrders the most open orders of an account on the symbol ({@code MAX_NUM_ORDERS})
 * @param maxNumAlgoOrders the most open stops of an account on the symbol ({@code MAX_NUM_ALGO_ORDERS})
 */
public record SymbolRules(
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal marketMinQuantity,
        BigDecimal marketMaxQuantity,
        BigDecimal marketStepSize,
        Boolean applyMinToMarket,
        BigDecimal maxNotional,
        Boolean applyMaxToMarket,
        BigDecimal bidMultiplierUp,
        BigDecimal bidMultiplierDown,
        BigDecimal askMultiplierUp,
        BigDecimal askMultiplierDown,
        Integer minTrailingAboveDelta,
        Integer maxTrailingAboveDelta,
        Integer minTrailingBelowDelta,
        Integer maxTrailingBelowDelta,
        Integer maxNumOrders,
        Integer maxNumAlgoOrders) {

    /** No rule beyond those of {@link PairFilters}: a market whose other filters are not known. */
    public static final SymbolRules NONE = new SymbolRules(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null);

    /** Decimals the columns of the prices, quantities and multipliers hold. */
    public static final int SCALE = 12;

    public SymbolRules {
        minPrice = rule(minPrice, "minPrice");
        maxPrice = rule(maxPrice, "maxPrice");
        minQuantity = rule(minQuantity, "minQuantity");
        maxQuantity = rule(maxQuantity, "maxQuantity");
        marketMinQuantity = rule(marketMinQuantity, "marketMinQuantity");
        marketMaxQuantity = rule(marketMaxQuantity, "marketMaxQuantity");
        marketStepSize = rule(marketStepSize, "marketStepSize");
        maxNotional = rule(maxNotional, "maxNotional");
        bidMultiplierUp = rule(bidMultiplierUp, "bidMultiplierUp");
        bidMultiplierDown = rule(bidMultiplierDown, "bidMultiplierDown");
        askMultiplierUp = rule(askMultiplierUp, "askMultiplierUp");
        askMultiplierDown = rule(askMultiplierDown, "askMultiplierDown");
        minTrailingAboveDelta = rule(minTrailingAboveDelta, "minTrailingAboveDelta");
        maxTrailingAboveDelta = rule(maxTrailingAboveDelta, "maxTrailingAboveDelta");
        minTrailingBelowDelta = rule(minTrailingBelowDelta, "minTrailingBelowDelta");
        maxTrailingBelowDelta = rule(maxTrailingBelowDelta, "maxTrailingBelowDelta");
        maxNumOrders = rule(maxNumOrders, "maxNumOrders");
        maxNumAlgoOrders = rule(maxNumAlgoOrders, "maxNumAlgoOrders");
    }

    /** Whether the minimum notional applies to a MARKET order. */
    public boolean minAppliesToMarket() {
        return applyMinToMarket == null || applyMinToMarket;
    }

    /** Whether the maximum notional applies to a MARKET order. */
    public boolean maxAppliesToMarket() {
        return applyMaxToMarket != null && applyMaxToMarket;
    }

    /** A figure the exchange turns off with zero: zero is {@code null}, a negative one or too many decimals refused. */
    private static BigDecimal rule(BigDecimal value, String name) {
        if (value == null || value.signum() == 0) {
            return null;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative, was " + value);
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > SCALE) {
            throw new IllegalArgumentException(
                    name + " has more than " + SCALE + " decimals, which its column would round: " + value);
        }
        // A whole number keeps scale 0, so 1E+3 is held as 1000.
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static Integer rule(Integer value, String name) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, was " + value);
        }
        return value;
    }
}
