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
 * A paper Futures conditional order, with the exchange's parameter names. Binance: {@code POST /fapi/v1/algoOrder}.
 *
 * <ul>
 *   <li>STOP and TAKE_PROFIT: {@code quantity}, {@code price} and {@code triggerPrice}; {@code timeInForce} GTC when
 *       absent. Once triggered they place a LIMIT order at {@code price}.
 *   <li>STOP_MARKET and TAKE_PROFIT_MARKET: {@code triggerPrice} and {@code quantity}, or {@code closePosition} to
 *       close the whole position. Once triggered they place a MARKET order.
 *   <li>TRAILING_STOP_MARKET is TR-21 and refused here.
 * </ul>
 *
 * <p>A stop BUY and a take-profit SELL trigger when the watched price is at or above {@code triggerPrice}, a stop SELL
 * and a take-profit BUY at or below it; an order that would trigger at once is refused ({@code -2021}). No margin is
 * checked until it triggers.
 *
 * @param algoType {@code CONDITIONAL}
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param side BUY or SELL
 * @param positionSide BOTH, or absent, in one-way mode
 * @param type STOP, STOP_MARKET, TAKE_PROFIT or TAKE_PROFIT_MARKET
 * @param timeInForce STOP and TAKE_PROFIT only
 * @param quantity the base quantity, absent with {@code closePosition}
 * @param price STOP and TAKE_PROFIT only
 * @param triggerPrice the price it triggers at, on the pair's tick
 * @param workingType {@code CONTRACT_PRICE} (the last price, when absent) or {@code MARK_PRICE}
 * @param priceProtect whether it waits while the mark and the last price are too far apart
 * @param reduceOnly whether it may only make the position smaller
 * @param closePosition whether it closes the whole position (market kinds; no quantity, no reduceOnly)
 * @param clientAlgoId the idempotency key, as the exchange's: up to 36 of {@code . A-Z : / a-z 0-9 _ -}
 * @param activatePrice a trailing stop's activation; refused here (TR-21)
 * @param callbackRate a trailing stop's callback; refused here (TR-21)
 */
public record FuturesAlgoOrderRequest(
        @NotBlank(message = "MSG01") @Pattern(regexp = "CONDITIONAL", message = "MSG15")
        String algoType,

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

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal triggerPrice,

        @Pattern(regexp = "CONTRACT_PRICE|MARK_PRICE", message = "MSG15")
        String workingType,

        Boolean priceProtect,

        Boolean reduceOnly,

        Boolean closePosition,

        @Pattern(regexp = "[.A-Z:/a-z0-9_-]{1,36}", message = "MSG15")
        String clientAlgoId,

        BigDecimal activatePrice,

        BigDecimal callbackRate) {

    /** Whether it closes the whole position when triggered. */
    public boolean closing() {
        return Boolean.TRUE.equals(closePosition);
    }

    /** Whether it may only make the position smaller. */
    public boolean reducing() {
        return Boolean.TRUE.equals(reduceOnly);
    }

    /** Whether it waits while the mark and the last price are too far apart. */
    public boolean protecting() {
        return Boolean.TRUE.equals(priceProtect);
    }
}
