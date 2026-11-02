package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * An amendment of a working paper Futures LIMIT order, with the exchange's parameter names. Binance:
 * {@code PUT /fapi/v1/order}. Both {@code quantity} and {@code price} are sent, as the exchange requires.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param orderId the order; or else {@code origClientOrderId}
 * @param origClientOrderId the client order id it was placed with
 * @param side BUY or SELL, the order's own side
 * @param quantity the new quantity asked for, on the pair's step; at most what executed cancels the order
 * @param price the new limit price, on the pair's tick
 * @param priceMatch not simulated; refused unless absent or NONE
 */
public record FuturesAmendOrderRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        UUID orderId,

        @Size(max = 64, message = "MSG15") String origClientOrderId,

        @NotNull(message = "MSG01") OrderSide side,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price,

        @Size(max = 32, message = "MSG15") String priceMatch) {}
