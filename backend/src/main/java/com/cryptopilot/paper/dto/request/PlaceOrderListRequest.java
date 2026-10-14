package com.cryptopilot.paper.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderListType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A group of paper Spot orders to place together (TR-10).
 *
 * <ul>
 *   <li>OCO: {@code oco} only. A take profit LIMIT and a STOP_LIMIT on the same side and quantity, placed now; one
 *       executing or triggering ends the other. For a SELL the take profit is above the last price and the stop below
 *       it; for a BUY the reverse.
 *   <li>OTO: {@code working} and {@code pending}. The working order (LIMIT or LIMIT_MAKER) is placed now; the pending
 *       one (LIMIT, LIMIT_MAKER or STOP_LIMIT) once the working order has filled in full.
 *   <li>OTOCO: {@code working} and {@code oco}: a limit with TP/SL, the OCO placed once the working order has filled.
 * </ul>
 *
 * <p>Prices are rounded to the pair's tick and quantities floored to its step, as for one order. Every LIMIT and
 * STOP_LIMIT of a group is GTC. Cancelling any order of a group cancels the group.
 *
 * @param pairId the pair, enabled on the market
 * @param market {@code SPOT}
 * @param type OCO, OTO or OTOCO
 * @param working OTO and OTOCO: the order placed now
 * @param pending OTO: the order placed once the working order has filled
 * @param oco OCO: the two orders placed now; OTOCO: the two orders placed once the working order has filled
 */
public record PlaceOrderListRequest(
        @NotNull(message = "MSG01") UUID pairId,
        @NotNull(message = "MSG01") MarketType market,
        @NotNull(message = "MSG01") OrderListType type,
        @Valid Leg working,
        @Valid Leg pending,
        @Valid Oco oco) {

    /**
     * One order of an OTO or OTOCO.
     *
     * @param side BUY or SELL
     * @param type LIMIT or LIMIT_MAKER; for the pending order of an OTO also STOP_LIMIT
     * @param price the limit price
     * @param stopPrice a STOP_LIMIT's stop; otherwise absent
     * @param quantity the base quantity
     */
    public record Leg(
            @NotNull(message = "MSG01") OrderSide side,
            @NotNull(message = "MSG01") OrderType type,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal price,

            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal stopPrice,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal quantity) {}

    /**
     * The two orders of an OCO, on one side and one quantity.
     *
     * @param side BUY or SELL
     * @param quantity the base quantity of each
     * @param takeProfitPrice the take profit LIMIT's price
     * @param stopPrice the STOP_LIMIT's stop
     * @param stopLimitPrice the STOP_LIMIT's limit price
     */
    public record Oco(
            @NotNull(message = "MSG01") OrderSide side,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal quantity,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal takeProfitPrice,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal stopPrice,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal stopLimitPrice) {}
}
