package com.cryptopilot.paper.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * The open price tab of the Futures calculator (TR-25): the entry price of a position opened in several parts. Nothing
 * is stored.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param lots the parts, 1 to 20, each a price and a quantity
 */
public record FuturesOpenPriceCalcRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        @NotEmpty(message = "MSG01") @Size(max = 20, message = "MSG15")
        List<@Valid @NotNull(message = "MSG01") Lot> lots) {

    /**
     * One part of the position.
     *
     * @param price the price it opened at
     * @param quantity the base quantity opened
     */
    public record Lot(
            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal price,

            @NotNull(message = "MSG01")
            @DecimalMin(value = "0", inclusive = false, message = "MSG15")
            @Digits(integer = 16, fraction = 12, message = "MSG15")
            BigDecimal quantity) {}
}
