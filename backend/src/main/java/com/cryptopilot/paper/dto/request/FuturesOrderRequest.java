package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A paper Futures order, with the exchange's parameter names. Binance: {@code POST /fapi/v1/order}.
 *
 * <ul>
 *   <li>LIMIT: {@code price} and {@code quantity}; {@code timeInForce} GTC when absent, or IOC, FOK or GTX (post-only,
 *       refused when it would execute on arrival).
 *   <li>MARKET: {@code quantity}; no {@code price} or {@code timeInForce}.
 *   <li>The conditional types (STOP, STOP_MARKET, TAKE_PROFIT, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET) are algo
 *       orders on the exchange and are refused here ({@code -4120}).
 * </ul>
 *
 * <p>Flags are JSON booleans rather than the exchange's strings, and the response is always the order's state after
 * placement ({@code newOrderRespType} RESULT); ACK is accepted and answered the same way.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param side BUY or SELL
 * @param positionSide BOTH, or absent, in one-way mode
 * @param type LIMIT or MARKET
 * @param timeInForce LIMIT only
 * @param quantity the base quantity, more than zero, on the pair's step
 * @param price LIMIT only, more than zero, on the pair's tick
 * @param reduceOnly whether it may only make the position smaller; one-way only
 * @param newClientOrderId the idempotency key, as the exchange's: up to 36 of {@code . A-Z : / a-z 0-9 _ -}
 * @param closePosition the exchange's flag of conditional orders; refused when true
 * @param stopPrice a conditional order's trigger; refused here
 * @param priceMatch not simulated; refused unless absent or NONE
 * @param newOrderRespType ACK or RESULT; both answered with the order's state
 * @param takeProfitPrice the trigger of a take profit placed for the whole position once this opening order has
 *     executed, as the app's TP/SL box (plan 5.6); absent for none
 * @param stopLossPrice likewise, the trigger of a stop loss
 * @param tpSlWorkingType the price the take profit and stop loss watch: CONTRACT_PRICE (the last price, when absent)
 *     or MARK_PRICE
 */
public record FuturesOrderRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        @NotNull(message = "MSG01") OrderSide side,

        PositionSide positionSide,

        @NotNull(message = "MSG01") OrderType type,

        TimeInForce timeInForce,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price,

        Boolean reduceOnly,

        @Pattern(regexp = "[.A-Z:/a-z0-9_-]{1,36}", message = "MSG15")
        String newClientOrderId,

        Boolean closePosition,

        BigDecimal stopPrice,

        @Size(max = 32, message = "MSG15") String priceMatch,

        @Pattern(regexp = "ACK|RESULT", message = "MSG15") String newOrderRespType,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal takeProfitPrice,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal stopLossPrice,

        @Pattern(regexp = "CONTRACT_PRICE|MARK_PRICE", message = "MSG15")
        String tpSlWorkingType) {

    /** Whether a take profit or a stop loss comes with the order. */
    public boolean withTpSl() {
        return takeProfitPrice != null || stopLossPrice != null;
    }

    /** Whether the order may only make the position smaller. */
    public boolean reducing() {
        return Boolean.TRUE.equals(reduceOnly);
    }
}
