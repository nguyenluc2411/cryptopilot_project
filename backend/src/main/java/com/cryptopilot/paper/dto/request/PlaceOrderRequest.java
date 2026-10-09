package com.cryptopilot.paper.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A paper order to place. This stage of TR-02 places Spot MARKET and LIMIT orders.
 *
 * <ul>
 *   <li>LIMIT: {@code price} and {@code quantity}; {@code timeInForce} GTC when absent, or IOC or FOK.
 *   <li>MARKET: {@code quantity}, or for a BUY {@code quoteOrderQty} instead, the quote amount to spend ("by total").
 * </ul>
 *
 * <p>The price is rounded to the pair's tick size (BR-30) and the quantity floored to its step size (BR-23), as the
 * order form of the exchange does; the order shows what was placed.
 *
 * @param pairId the pair, enabled on the market
 * @param market {@code SPOT}
 * @param side BUY or SELL
 * @param type MARKET or LIMIT
 * @param timeInForce LIMIT only: GTC, IOC or FOK
 * @param price LIMIT only: the price, more than zero
 * @param quantity the base quantity, more than zero
 * @param quoteOrderQty MARKET BUY only, instead of {@code quantity}: what to spend, in the quote asset
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
        String clientOrderId) {}
