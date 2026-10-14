package com.cryptopilot.paper.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A paper Spot order to place.
 *
 * <ul>
 *   <li>LIMIT: {@code price} and {@code quantity}; {@code timeInForce} GTC when absent, or IOC, FOK or GTX.
 *   <li>LIMIT_MAKER: {@code price} and {@code quantity}; no {@code timeInForce} (TR-12).
 *   <li>MARKET: {@code quantity}, or for a BUY {@code quoteOrderQty} instead, the quote amount to spend ("by total").
 *   <li>STOP_LIMIT: {@code stopPrice}, {@code price} and {@code quantity}; {@code timeInForce} GTC when absent, or IOC
 *       or FOK. It triggers when the last price trades at or past the stop, on the side of the last price the stop
 *       lies when it is placed (TR-09).
 *   <li>TRAILING_STOP_LIMIT: {@code callbackRate}, {@code price} and {@code quantity}, optionally
 *       {@code activationPrice}; {@code timeInForce} as for STOP_LIMIT. A SELL follows the highest price and triggers
 *       when the price falls back by the callback, a BUY the reverse (TR-11).
 * </ul>
 *
 * <p>The price is rounded to the pair's tick size (BR-30) and the quantity floored to its step size (BR-23), as the
 * order form of the exchange does; the order shows what was placed.
 *
 * @param pairId the pair, enabled on the market
 * @param market {@code SPOT}
 * @param side BUY or SELL
 * @param type MARKET, LIMIT, LIMIT_MAKER, STOP_LIMIT or TRAILING_STOP_LIMIT
 * @param timeInForce GTC, IOC or FOK (and GTX for a LIMIT); absent for MARKET and LIMIT_MAKER
 * @param price LIMIT only: the price, more than zero
 * @param quantity the base quantity, more than zero
 * @param quoteOrderQty MARKET BUY only, instead of {@code quantity}: what to spend, in the quote asset
 * @param stopPrice STOP_LIMIT only: the last price that triggers it, more than zero and not the last price itself
 * @param callbackRate TRAILING_STOP_LIMIT only: how far back from its best price it triggers, in percent, from 0.1 to
 *     20
 * @param activationPrice TRAILING_STOP_LIMIT only, optional: the price that activates it; absent, it is active at once
 * @param clientOrderId the idempotency key: a request sent again with the same key, e.g. after a timeout, returns the
 *     order already placed instead of placing a second one; up to 64 letters, digits and {@code . _ : -}. Optional
 */
public record PlaceOrderRequest(
        @NotNull(message = "MSG01") UUID pairId,

        @NotNull(message = "MSG01") MarketType market,

        @NotNull(message = "MSG01") OrderSide side,

        @NotNull(message = "MSG01") OrderType type,

        TimeInForce timeInForce,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal quoteOrderQty,

        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "MSG15")
        String clientOrderId,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal stopPrice,

        @DecimalMin(value = "0.1", message = "MSG15")
        @DecimalMax(value = "20", message = "MSG15")
        @Digits(integer = 2, fraction = 8, message = "MSG15")
        BigDecimal callbackRate,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal activationPrice) {}
