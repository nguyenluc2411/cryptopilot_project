package com.cryptopilot.paper.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * The new terms of a working Spot LIMIT or LIMIT_MAKER order, which is cancelled and placed again (B4, the exchange's
 * {@code cancelReplace} with {@code STOP_ON_FAILURE}): the new order keeps the side, type and time in force of the one
 * it replaces.
 *
 * @param price the new limit price, on the pair's tick
 * @param quantity the new quantity, on the pair's step; when absent, what the replaced order has left to fill
 */
public record ReplaceOrderRequest(
        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal price,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity) {}
